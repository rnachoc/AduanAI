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
    if (valid.isEmpty()) {
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
    if (decision instanceof Decision.RequireApproval approval) {
      Optional<JsonNode> refused =
          awaitApproval(principal, offered, name, decided, approval, intent);
      if (refused.isPresent()) {
        return refused.get();
      }
    } else {
      if (!record(principal, intent)) {
        return errors.toolResult(AgentErrorCode.AUDIT_UNAVAILABLE);
      }
      if (decision instanceof Decision.Deny) {
        return errors.toolResult(AgentErrorCode.POLICY_DENIED);
      }
    }
    ObjectNode forward = json.createObjectNode();
    forward.put("name", name.tool());
    forward.set("arguments", forwardedArguments);
    UpstreamServer server = offered.server().upstream();
    long started = System.nanoTime();
    UpstreamResponse response = upstreams.send(server, "tools/call", forward, offered.credential());
    long latency = (System.nanoTime() - started) / 1_000_000;
    Completion completion =
        new Completion(
            principal, offered, name, decided.bundleSha256(), decision.obligations(), traceKey);
    return complete(completion, server, response, latency);
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
