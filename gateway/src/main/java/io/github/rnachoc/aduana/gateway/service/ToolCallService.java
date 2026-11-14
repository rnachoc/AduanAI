// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.service;

import io.github.rnachoc.aduana.gateway.approval.ApprovalService;
import io.github.rnachoc.aduana.gateway.audit.AuditLog;
import io.github.rnachoc.aduana.gateway.audit.AuditRecord;
import io.github.rnachoc.aduana.gateway.catalog.ArgumentValidator;
import io.github.rnachoc.aduana.gateway.catalog.CatalogService;
import io.github.rnachoc.aduana.gateway.config.AduanaProperties;
import io.github.rnachoc.aduana.gateway.error.AgentErrorCode;
import io.github.rnachoc.aduana.gateway.error.AgentErrors;
import io.github.rnachoc.aduana.gateway.identity.Principal;
import io.github.rnachoc.aduana.gateway.inspection.Detectors;
import io.github.rnachoc.aduana.gateway.inspection.Redactor;
import io.github.rnachoc.aduana.gateway.inspection.ResultInspector;
import io.github.rnachoc.aduana.gateway.inspection.TaintService;
import io.github.rnachoc.aduana.gateway.limits.IdempotencyService;
import io.github.rnachoc.aduana.gateway.limits.LimitService;
import io.github.rnachoc.aduana.gateway.mcp.McpProtocol;
import io.github.rnachoc.aduana.gateway.policy.PolicyService;
import io.github.rnachoc.aduana.gateway.upstream.UpstreamClient;
import io.github.rnachoc.aduana.gateway.upstream.UpstreamResponse;
import io.github.rnachoc.aduana.gateway.upstream.UpstreamServer;
import io.github.rnachoc.aduana.policy.CallContext;
import io.github.rnachoc.aduana.policy.Decision;
import io.github.rnachoc.aduana.policy.Rule;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs one `tools/call` from an agent: resolve the tool in the catalog, check the arguments against
 * the pinned schema and size limit, ask the policy, forward with the injected credential, map
 * errors.
 *
 * <p>Arguments are always written to the log redacted; the policy's obligations deny or flag
 * sensitive arguments and redact results; values from untrusted results are tracked into later
 * arguments (plan §5.6, §5.7).
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Service
public class ToolCallService {

  private static final Logger LOG = LoggerFactory.getLogger(ToolCallService.class);

  private final AduanaProperties properties;
  private final CatalogService catalog;
  private final ArgumentValidator validator;
  private final PolicyService policies;
  private final AuditLog audit;
  private final ApprovalService approvals;
  private final TaintService taints;
  private final ResultInspector inspector;
  private final LimitService limits;
  private final IdempotencyService idempotency;
  private final UpstreamClient upstreams;
  private final AgentErrors errors;
  private final JsonMapper json;

  /**
   * Creates the service.
   *
   * @param properties size limits
   * @param catalog the catalog
   * @param validator checks arguments against pinned schemas
   * @param policies decides calls with the active policy
   * @param audit records every decision before anything is forwarded
   * @param approvals holds calls that need a person's approval
   * @param taints tracks values from untrusted results
   * @param inspector redacts and marks results
   * @param limits counts call rates and budgets
   * @param idempotency remembers results of calls sent with an idempotency key
   * @param upstreams the upstream client
   * @param errors builds agent-facing errors
   * @param json the shared JSON mapper
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ToolCallService(
      AduanaProperties properties,
      CatalogService catalog,
      ArgumentValidator validator,
      PolicyService policies,
      AuditLog audit,
      ApprovalService approvals,
      TaintService taints,
      ResultInspector inspector,
      LimitService limits,
      IdempotencyService idempotency,
      UpstreamClient upstreams,
      AgentErrors errors,
      JsonMapper json) {
    this.properties = properties;
    this.catalog = catalog;
    this.validator = validator;
    this.policies = policies;
    this.audit = audit;
    this.approvals = approvals;
    this.taints = taints;
    this.inspector = inspector;
    this.limits = limits;
    this.idempotency = idempotency;
    this.upstreams = upstreams;
    this.errors = errors;
    this.json = json;
  }

  /**
   * Runs a call and returns the `tools/call` result for the agent.
   *
   * @param principal the caller
   * @param params the agent's `tools/call` parameters
   * @return the upstream's result, or an error result
   * @throws io.github.rnachoc.aduana.gateway.mcp.McpException TOOL_NOT_FOUND for a tool that does
   *     not exist or is not offered, without saying which
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public JsonNode call(Principal principal, ObjectNode params) {
    String requested = params.path("name").asString("");
    Optional<CatalogService.OfferedTool> found =
        ToolName.parse(requested)
            .flatMap(name -> catalog.offeredTool(principal.tenantId(), name.server(), name.tool()));
    if (found.isEmpty()) {
      record(
          principal,
          AuditRecord.of("TOOL_NOT_FOUND").principal(principal.id()).tool(null, requested));
      throw errors.toolNotFound();
    }
    CatalogService.OfferedTool offered = found.get();
    ToolName name = new ToolName(offered.server().name(), offered.tool().name());
    JsonNode arguments = params.path("arguments");
    Optional<ObjectNode> valid = validArguments(offered, arguments);
    Optional<Optional<String>> key = idempotencyKey(params);
    if (valid.isEmpty() || key.isEmpty()) {
      record(
          principal,
          AuditRecord.of("ARGS_INVALID")
              .principal(principal.id())
              .tool(name.server(), name.qualified()));
      return errors.toolResult(AgentErrorCode.ARGS_INVALID);
    }
    ObjectNode forwardedArguments = valid.get();
    String traceKey = traceKey(params);
    Redactor.Redacted logged =
        Redactor.redact(forwardedArguments, Detectors.IDS, Redactor.Style.WITH_HINT);
    TaintService.Marks taint =
        taints.check(principal.tenantId(), principal.id(), traceKey, forwardedArguments);
    PolicyService.Decided decided =
        policies.decide(
            principal,
            offered.tool(),
            forwardedArguments,
            new CallContext.ContextFacts(taint.flowFromUntrusted(), taint.contextTainted()));
    Decision decision = applyArgumentObligations(decided.decision(), logged.found());
    AuditRecord.Builder intent =
        AuditRecord.of("CALL_DECIDED")
            .principal(principal.id())
            .tool(name.server(), name.qualified())
            .arguments(AuditLog.hashOf(forwardedArguments), logged.value())
            .decision(
                decision.effect().yamlName(),
                decision.matchedRules(),
                decision.reason().orElse(null),
                decided.bundleSha256())
            .flags(callFlags(decision, logged.found(), taint));
    Call call =
        new Call(principal, offered, name, decided, decision, intent, forwardedArguments, traceKey);
    if (decision instanceof Decision.Deny || key.get().isEmpty()) {
      Optional<JsonNode> refused =
          decision instanceof Decision.Deny ? Optional.empty() : limit(call);
      return refused.orElseGet(() -> proceed(call).result());
    }
    return idempotent(call, key.get().get());
  }

  /**
   * Everything the later steps of a call need.
   *
   * @param principal the caller
   * @param offered the tool
   * @param name the tool name
   * @param decided the policy decision and its bundle
   * @param decision the decision after argument obligations
   * @param intent the `CALL_DECIDED` entry
   * @param arguments the arguments to forward
   * @param traceKey the call's trace id, or empty
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private record Call(
      Principal principal,
      CatalogService.OfferedTool offered,
      ToolName name,
      PolicyService.Decided decided,
      Decision decision,
      AuditRecord.Builder intent,
      ObjectNode arguments,
      String traceKey) {}

  /**
   * What a call answered, and whether it reached the upstream.
   *
   * @param result the agent-facing result
   * @param forwarded true when the upstream may have run the call
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private record Answer(JsonNode result, boolean forwarded) {}

  /**
   * Runs a call that carries an idempotency key: the first call with the key runs and its result is
   * kept; a retry with the same arguments gets that result without reaching the upstream.
   *
   * @param call the call
   * @param key the idempotency key
   * @return the result
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private JsonNode idempotent(Call call, String key) {
    Principal principal = call.principal();
    String argsHash = AuditLog.hashOf(call.arguments());
    IdempotencyService.Claim claim;
    try {
      claim = idempotency.claim(principal.tenantId(), principal.id(), key, argsHash);
    } catch (DataAccessException exception) {
      LOG.error("idempotency store unavailable, nothing forwarded");
      return errors.toolResult(AgentErrorCode.AUDIT_UNAVAILABLE);
    }
    if (!(claim instanceof IdempotencyService.Claim.Owner)) {
      boolean replay = claim instanceof IdempotencyService.Claim.Replay;
      AuditRecord.Builder entry =
          AuditRecord.of("IDEMPOTENT_REPLAY")
              .principal(principal.id())
              .tool(call.name().server(), call.name().qualified())
              .arguments(argsHash, call.intent().build().argsRedacted().orElseThrow());
      if (!replay) {
        entry.reason("IDEMPOTENCY_CONFLICT");
      }
      if (!record(principal, entry)) {
        return errors.toolResult(AgentErrorCode.AUDIT_UNAVAILABLE);
      }
      return claim instanceof IdempotencyService.Claim.Replay(JsonNode stored)
          ? stored
          : errors.toolResult(AgentErrorCode.IDEMPOTENCY_CONFLICT);
    }
    Answer answer = null;
    try {
      Optional<JsonNode> refused = limit(call);
      if (refused.isPresent()) {
        return refused.get();
      }
      answer = proceed(call);
      return answer.result();
    } finally {
      settle(principal, key, answer);
    }
  }

  /**
   * Keeps the result of a call that reached the upstream, or frees the key of one that did not, so
   * that a retry runs it.
   *
   * @param principal the caller
   * @param key the idempotency key
   * @param answer the answer, or null when the call stopped before one
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private void settle(Principal principal, String key, Answer answer) {
    try {
      if (answer != null && answer.forwarded()) {
        idempotency.complete(principal.tenantId(), principal.id(), key, answer.result());
      } else {
        idempotency.release(principal.tenantId(), principal.id(), key);
      }
    } catch (DataAccessException exception) {
      LOG.error("idempotency store unavailable after the call");
    }
  }

  /**
   * Counts the call against its rate and budget limits.
   *
   * @param call the call
   * @return the error result when a limit is reached, or empty
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Optional<JsonNode> limit(Call call) {
    Principal principal = call.principal();
    Optional<LimitService.Refusal> refusal;
    try {
      refusal =
          limits.admit(
              principal.tenantId(),
              principal.id(),
              call.name().qualified(),
              call.offered().tool().cost());
    } catch (DataAccessException exception) {
      LOG.error("limit counters unavailable, nothing forwarded");
      return Optional.of(errors.toolResult(AgentErrorCode.AUDIT_UNAVAILABLE));
    }
    if (refusal.isEmpty()) {
      return Optional.empty();
    }
    LimitService.Refusal refused = refusal.get();
    AuditRecord.Builder entry =
        AuditRecord.of("RATE_LIMITED")
            .principal(principal.id())
            .tool(call.name().server(), call.name().qualified())
            .reason(refused.code());
    if (!record(principal, entry)) {
      return Optional.of(errors.toolResult(AgentErrorCode.AUDIT_UNAVAILABLE));
    }
    return Optional.of(
        errors.toolResult(
            AgentErrorCode.valueOf(refused.code()),
            Map.of("retry_after_ms", refused.retryAfter().toMillis())));
  }

  /**
   * Approval, the intent entry, forwarding and the result: steps 8 to 12.
   *
   * @param call the call
   * @return the answer
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Answer proceed(Call call) {
    Principal principal = call.principal();
    Decision decision = call.decision();
    if (decision instanceof Decision.RequireApproval approval) {
      Optional<JsonNode> refused =
          awaitApproval(
              principal, call.offered(), call.name(), call.decided(), approval, call.intent());
      if (refused.isPresent()) {
        return new Answer(refused.get(), false);
      }
    } else {
      if (!record(principal, call.intent())) {
        return new Answer(errors.toolResult(AgentErrorCode.AUDIT_UNAVAILABLE), false);
      }
      if (decision instanceof Decision.Deny) {
        return new Answer(errors.toolResult(AgentErrorCode.POLICY_DENIED), false);
      }
    }
    ObjectNode forward = json.createObjectNode();
    forward.put("name", call.name().tool());
    forward.set("arguments", call.arguments());
    UpstreamServer server = call.offered().server().upstream();
    long started = System.nanoTime();
    UpstreamResponse response =
        upstreams.send(server, "tools/call", forward, call.offered().credential());
    long latency = (System.nanoTime() - started) / 1_000_000;
    Completion completion =
        new Completion(
            principal,
            call.offered(),
            call.name(),
            call.decided().bundleSha256(),
            decision.obligations(),
            call.traceKey());
    JsonNode result = complete(completion, server, response, latency);
    return new Answer(result, !(response instanceof UpstreamResponse.Unavailable));
  }

  /**
   * Reads the idempotency key from `_meta["aduana/idempotency-key"]`.
   *
   * @param params the call parameters
   * @return empty when the key is present but not a string of 1 to 200 characters; otherwise the
   *     key, or an empty optional when there is none
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static Optional<Optional<String>> idempotencyKey(ObjectNode params) {
    JsonNode key = params.path("_meta").path(IdempotencyService.META_KEY);
    if (key.isMissingNode() || key.isNull()) {
      return Optional.of(Optional.empty());
    }
    if (!key.isString() || key.asString().isEmpty() || key.asString().length() > 200) {
      return Optional.empty();
    }
    return Optional.of(Optional.of(key.asString()));
  }

  /**
   * Gets a person's approval for this exact call, or says why the call cannot go ahead yet.
   *
   * <p>When the approval is granted, it is consumed in the same transaction that records the call,
   * so a granted approval authorises exactly one forwarded call.
   *
   * @param principal the caller
   * @param offered the tool
   * @param name the tool name
   * @param decided the policy decision and its bundle
   * @param approval the approval the policy asks for
   * @param intent the `CALL_DECIDED` entry, without the approval id yet
   * @return the error result to answer with, or empty when the call may be forwarded
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Optional<JsonNode> awaitApproval(
      Principal principal,
      CatalogService.OfferedTool offered,
      ToolName name,
      PolicyService.Decided decided,
      Decision.RequireApproval approval,
      AuditRecord.Builder intent) {
    AuditRecord draft = intent.build();
    ApprovalService.Outcome outcome;
    try {
      outcome =
          approvals.obtain(
              new ApprovalService.Request(
                  principal,
                  offered.tool(),
                  name.qualified(),
                  draft.argsHash().orElseThrow(),
                  draft.argsRedacted().orElseThrow(),
                  decided.bundleSha256(),
                  approval,
                  draft.flags()));
    } catch (DataAccessException exception) {
      LOG.error("approval could not be recorded, nothing forwarded");
      return Optional.of(errors.toolResult(AgentErrorCode.AUDIT_UNAVAILABLE));
    }
    Map<String, String> id = Map.of("approval_id", outcome.id().toString());
    intent.approval(outcome.id());
    if (outcome instanceof ApprovalService.Outcome.Approved
        && approvals.consume(principal.tenantId(), outcome.id(), intent.build())) {
      return Optional.empty();
    }
    boolean rejected = outcome instanceof ApprovalService.Outcome.Rejected;
    intent.reason(rejected ? "APPROVAL_REJECTED" : "APPROVAL_PENDING");
    if (!record(principal, intent)) {
      return Optional.of(errors.toolResult(AgentErrorCode.AUDIT_UNAVAILABLE));
    }
    return Optional.of(
        errors.toolResult(
            rejected ? AgentErrorCode.APPROVAL_REJECTED : AgentErrorCode.APPROVAL_PENDING, id));
  }

  /**
   * Checks the arguments: an object, within the size limit, valid against the pinned schema.
   *
   * @param offered the tool
   * @param arguments the arguments as sent
   * @return the arguments to forward, or empty when they are invalid
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Optional<ObjectNode> validArguments(
      CatalogService.OfferedTool offered, JsonNode arguments) {
    if (!arguments.isMissingNode() && !arguments.isObject()) {
      return Optional.empty();
    }
    ObjectNode object = arguments.isObject() ? (ObjectNode) arguments : json.createObjectNode();
    if (json.writeValueAsBytes(object).length > properties.limits().maxArgumentsBytes()) {
      return Optional.empty();
    }
    List<String> problems = validator.validate(offered.tool(), object, offered.server().strict());
    return problems.isEmpty() ? Optional.of(object) : Optional.empty();
  }

  /**
   * Appends an entry, reporting instead of throwing when the log is unavailable.
   *
   * @param principal the caller, for the tenant
   * @param record the entry
   * @return true when the entry was recorded
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private boolean record(Principal principal, AuditRecord.Builder record) {
    try {
      audit.append(principal.tenantId(), record.build());
      return true;
    } catch (DataAccessException exception) {
      LOG.error(
          "audit log unavailable, nothing forwarded: {}",
          exception.getMostSpecificCause().getMessage());
      return false;
    }
  }

  /**
   * What the end of a forwarded call needs to know.
   *
   * @param principal the caller
   * @param offered the tool
   * @param name the tool name
   * @param bundle the bundle that allowed the call
   * @param obligations what the policy requires of the result
   * @param traceKey the call's trace id, or empty
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private record Completion(
      Principal principal,
      CatalogService.OfferedTool offered,
      ToolName name,
      String bundle,
      Rule.Obligations obligations,
      String traceKey) {}

  /**
   * Inspects the result (redaction, injection marks), records how the call ended, remembers the
   * values of an untrusted result, and maps the upstream's answer for the agent.
   *
   * @param completion the call
   * @param server the upstream
   * @param response the upstream's answer
   * @param latency milliseconds the upstream took
   * @return the agent-facing result
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private JsonNode complete(
      Completion completion, UpstreamServer server, UpstreamResponse response, long latency) {
    Principal principal = completion.principal();
    ToolName name = completion.name();
    String bundle = completion.bundle();
    AuditRecord.Builder outcome =
        AuditRecord.of("CALL_COMPLETED")
            .principal(principal.id())
            .tool(name.server(), name.qualified())
            .bundle(bundle);
    JsonNode result;
    boolean unknownTool = false;
    JsonNode untrusted = null;
    switch (response) {
      case UpstreamResponse.Result(JsonNode value)
          when json.writeValueAsBytes(value).length > properties.limits().maxResultBytes() -> {
        LOG.warn("upstream {} answered a result over the size limit", server.name());
        outcome = failed(principal, name, bundle, "RESULT_TOO_LARGE", latency);
        result = errors.toolResult(AgentErrorCode.UPSTREAM_ERROR);
      }
      case UpstreamResponse.Result(JsonNode value) -> {
        ResultInspector.Inspected inspected =
            inspector.inspect(value, List.copyOf(completion.obligations().redactResult()));
        outcome.result(AuditLog.hashOf(value), latency).flags(inspected.flags());
        result = inspected.result();
        untrusted = completion.offered().tool().untrustedSource() ? value : null;
      }
      case UpstreamResponse.RpcError(int code, String message)
          when code == McpProtocol.INVALID_PARAMS && message.startsWith("Unknown tool") -> {
        outcome = failed(principal, name, bundle, "TOOL_NOT_FOUND", latency);
        unknownTool = true;
        result = json.nullNode();
      }
      case UpstreamResponse.RpcError(int code, String message) -> {
        LOG.warn("upstream {} answered error {}", server.name(), code);
        outcome = failed(principal, name, bundle, "UPSTREAM_ERROR", latency);
        result = errors.toolResult(AgentErrorCode.UPSTREAM_ERROR);
      }
      case UpstreamResponse.Unavailable(String detail) -> {
        LOG.warn("upstream {} unavailable: {}", server.name(), detail);
        outcome = failed(principal, name, bundle, "UPSTREAM_ERROR", latency);
        result = errors.toolResult(AgentErrorCode.UPSTREAM_ERROR);
      }
      case UpstreamResponse.TimedOut() -> {
        LOG.warn("upstream {} timed out after {}", server.name(), server.timeout());
        outcome = failed(principal, name, bundle, "UPSTREAM_TIMEOUT", latency);
        result = errors.toolResult(AgentErrorCode.UPSTREAM_TIMEOUT);
      }
    }
    Optional<Long> seq = recordSeq(principal, outcome);
    if (untrusted != null && seq.isPresent()) {
      taints.record(
          principal.tenantId(), principal.id(), completion.traceKey(), untrusted, seq.get());
    }
    if (unknownTool) {
      throw errors.toolNotFound();
    }
    return result;
  }

  /**
   * Turns an allow or an approval into a deny when the arguments contain a value of a detector the
   * matching rules list under `deny_args_containing`.
   *
   * @param decision the policy's decision
   * @param found detectors that fired on the arguments
   * @return the decision to apply
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Decision applyArgumentObligations(Decision decision, Set<String> found) {
    Set<String> forbidden = new TreeSet<>(decision.obligations().denyArgsContaining());
    forbidden.retainAll(found);
    if (forbidden.isEmpty()) {
      return decision;
    }
    return new Decision.Deny(
        "ARGS_CONTAIN_" + forbidden.iterator().next().toUpperCase(Locale.ROOT),
        decision.matchedRules(),
        decision.typeMismatches());
  }

  /**
   * Builds the flags of a `CALL_DECIDED` entry.
   *
   * @param decision the decision
   * @param found detectors that fired on the arguments
   * @param taint the taint marks
   * @return the flags, including where each tainted argument came from
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static List<String> callFlags(
      Decision decision, Set<String> found, TaintService.Marks taint) {
    List<String> flags = new ArrayList<>();
    if (!decision.typeMismatches().isEmpty()) {
      flags.add("POLICY_TYPE_MISMATCH");
    }
    Set<String> flagged = new TreeSet<>(decision.obligations().flagArgsContaining());
    flagged.retainAll(found);
    flagged.forEach(id -> flags.add("SENSITIVE_" + id.toUpperCase(Locale.ROOT)));
    if (!found.isEmpty()) {
      flags.add("REDACTED");
    }
    if (taint.contextTainted()) {
      flags.add("CONTEXT_TAINTED");
    }
    if (taint.flowFromUntrusted()) {
      flags.add("FLOW_FROM_UNTRUSTED");
      taint
          .sources()
          .forEach(
              source -> flags.add("TAINT_SOURCE:" + source.path() + ":seq=" + source.sourceSeq()));
    }
    return flags;
  }

  /**
   * Reads the trace id from `_meta.traceparent` (W3C Trace Context: version-traceid-parent-flags).
   *
   * @param params the call parameters
   * @return the 32-character trace id, or empty
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static String traceKey(ObjectNode params) {
    String traceparent = params.path("_meta").path("traceparent").asString("");
    String[] parts = traceparent.split("-");
    return parts.length == 4 && parts[1].matches("[0-9a-f]{32}") ? parts[1] : "";
  }

  /**
   * Appends an entry and returns its seq, or empty when the log is unavailable.
   *
   * @param principal the caller
   * @param record the entry
   * @return the seq
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Optional<Long> recordSeq(Principal principal, AuditRecord.Builder record) {
    try {
      return Optional.of(audit.append(principal.tenantId(), record.build()).seq());
    } catch (DataAccessException exception) {
      LOG.error(
          "audit log unavailable after the call: {}",
          exception.getMostSpecificCause().getMessage());
      return Optional.empty();
    }
  }

  /**
   * Starts a `CALL_FAILED` entry.
   *
   * @param principal the caller
   * @param name the tool
   * @param bundle the bundle that allowed the call
   * @param reason the failure code
   * @param latency milliseconds spent
   * @return the entry builder
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static AuditRecord.Builder failed(
      Principal principal, ToolName name, String bundle, String reason, long latency) {
    return AuditRecord.of("CALL_FAILED")
        .principal(principal.id())
        .tool(name.server(), name.qualified())
        .bundle(bundle)
        .reason(reason)
        .latency(latency);
  }
}
