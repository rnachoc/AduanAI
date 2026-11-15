// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.policy;

import io.github.rnachoc.aduana.gateway.audit.AuditFilter;
import io.github.rnachoc.aduana.gateway.audit.AuditLog;
import io.github.rnachoc.aduana.gateway.catalog.CatalogService;
import io.github.rnachoc.aduana.gateway.catalog.CatalogTool;
import io.github.rnachoc.aduana.gateway.identity.Principal;
import io.github.rnachoc.aduana.gateway.identity.PrincipalService;
import io.github.rnachoc.aduana.policy.ArgCondition;
import io.github.rnachoc.aduana.policy.CallContext;
import io.github.rnachoc.aduana.policy.Decision;
import io.github.rnachoc.aduana.policy.PolicyBundle;
import io.github.rnachoc.aduana.policy.PolicyEvaluator;
import io.github.rnachoc.aduana.policy.PolicyParser;
import io.github.rnachoc.aduana.policy.Rule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Replays logged decisions against a candidate policy (plan Phase 10).
 *
 * <p>Each `CALL_DECIDED` entry is rebuilt into a call context: the principal's and the tool's
 * current facts, the logged flags for taint, and the logged arguments, which are redacted. When a
 * condition reads a redacted value and the outcome depends on it, the decision is unknown: it is
 * reported apart and never counted as unchanged. Detectors that fired are recovered from the
 * redaction placeholders, so `deny_args_containing` applies as it did live.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Service
public class PolicySimulator {

  /** The most entries one simulation reads. */
  public static final int MAX_ENTRIES = 20_000;

  private static final int EXAMPLES = 3;
  private static final Pattern PLACEHOLDER = Pattern.compile("\\[REDACTED:([a-z_]+)");

  private final AuditLog audit;
  private final PrincipalService principals;
  private final CatalogService catalog;

  /**
   * The result.
   *
   * @param candidateSha256 the candidate bundle's hash
   * @param firstSeq the first log entry read
   * @param lastSeq the last log entry read
   * @param decisions logged decisions examined
   * @param changed decisions whose effect would change
   * @param unchanged decisions whose effect would stay
   * @param unknown decisions that depend on redacted arguments or on facts no longer known
   * @param changes changes grouped by the rules the candidate matched, with example entries
   * @param unknowns the unknown decisions and why
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Result(
      String candidateSha256,
      long firstSeq,
      long lastSeq,
      int decisions,
      int changed,
      int unchanged,
      int unknown,
      List<Change> changes,
      List<Unknown> unknowns) {}

  /**
   * One group of changed decisions.
   *
   * @param rules the candidate's matching rules, or `(default)`
   * @param before the logged effect
   * @param after the candidate's effect
   * @param count how many decisions
   * @param examples up to three of them
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Change(
      String rules, String before, String after, int count, List<Example> examples) {}

  /**
   * An example decision.
   *
   * @param seq the log entry
   * @param tool the tool
   * @param principal the agent's name
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Example(long seq, String tool, String principal) {}

  /**
   * A decision the simulation cannot judge.
   *
   * @param seq the log entry
   * @param tool the tool
   * @param reason what is missing, such as `REDACTED_ARGUMENT $.text`
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Unknown(long seq, String tool, String reason) {}

  /**
   * Outcome of a request: a result or the candidate's parse errors.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public sealed interface Outcome {

    /**
     * The simulation ran.
     *
     * @param result its result
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    record Ran(Result result) implements Outcome {}

    /**
     * The candidate does not parse.
     *
     * @param errors why
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    record Invalid(List<String> errors) implements Outcome {}
  }

  /**
   * Creates the simulator.
   *
   * @param audit the log
   * @param principals the principals
   * @param catalog the catalog
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public PolicySimulator(AuditLog audit, PrincipalService principals, CatalogService catalog) {
    this.audit = audit;
    this.principals = principals;
    this.catalog = catalog;
  }

  /**
   * Simulates a candidate over a range of the log.
   *
   * @param tenantId the tenant
   * @param source the candidate policy and tests
   * @param fromSeq the first entry, or empty for the newest {@value #MAX_ENTRIES} entries
   * @param toSeq the last entry, or empty for the end
   * @return the result, or why the candidate is invalid
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Outcome simulate(
      UUID tenantId, PolicySource source, Optional<Long> fromSeq, Optional<Long> toSeq) {
    PolicyParser.Result parsed = PolicyParser.parse(source.policy(), source.tests());
    if (parsed instanceof PolicyParser.Result.Invalid(List<String> errors)) {
      return new Outcome.Invalid(errors);
    }
    PolicyBundle bundle = ((PolicyParser.Result.Valid) parsed).bundle();
    Map<String, Principal> byId = new HashMap<>();
    principals.list(tenantId).forEach(p -> byId.put(p.id().toString(), p));
    Map<String, CatalogTool> tools = new HashMap<>();
    catalog.tools(tenantId).forEach(t -> tools.put(t.serverName() + "__" + t.name(), t));
    List<ObjectNode> entries =
        audit.entries(
            tenantId,
            new AuditFilter(
                Math.max(
                    1,
                    fromSeq.orElseGet(() ->
                        audit.head(tenantId).map(head -> head.seq()).orElse(0L) - MAX_ENTRIES + 1)),
                MAX_ENTRIES,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));
    int decisions = 0;
    int unchanged = 0;
    long firstSeq = 0;
    long lastSeq = 0;
    Map<String, List<Example>> groups = new LinkedHashMap<>();
    List<Unknown> unknowns = new ArrayList<>();
    for (ObjectNode entry : entries) {
      long seq = entry.path("seq").asLong();
      if (toSeq.isPresent() && seq > toSeq.get()) {
        break;
      }
      firstSeq = firstSeq == 0 ? seq : firstSeq;
      lastSeq = seq;
      if (!"CALL_DECIDED".equals(entry.path("type").asString())) {
        continue;
      }
      decisions++;
      String toolName = entry.path("tool").asString("");
      Principal principal = byId.get(entry.path("principal_id").asString(""));
      CatalogTool tool = tools.get(toolName);
      if (principal == null || tool == null) {
        unknowns.add(
            new Unknown(seq, toolName, principal == null ? "PRINCIPAL_GONE" : "TOOL_GONE"));
        continue;
      }
      CallContext context =
          new CallContext(
              PolicyService.facts(principal),
              PolicyService.facts(tool),
              entry.path("args_redacted"),
              contextFacts(entry));
      Set<String> found = detectors(entry.path("args_redacted"));
      Optional<Decision> decided = decideOverRedactions(bundle, context, found);
      if (decided.isEmpty()) {
        unknowns.add(
            new Unknown(
                seq,
                toolName,
                "REDACTED_ARGUMENT " + String.join(", ", redactedPaths(bundle, context))));
        continue;
      }
      Decision after = decided.get();
      String before = entry.path("decision").asString("");
      String afterName = after.effect().yamlName();
      if (afterName.equals(before)) {
        unchanged++;
        continue;
      }
      String rules =
          after.matchedRules().isEmpty() ? "(default)" : String.join(", ", after.matchedRules());
      groups
          .computeIfAbsent(
              rules + "\u0000" + before + "\u0000" + afterName, key -> new ArrayList<>())
          .add(new Example(seq, toolName, principal.name()));
    }
    List<Change> changes = new ArrayList<>();
    int changed = 0;
    for (Map.Entry<String, List<Example>> group : groups.entrySet()) {
      String[] key = group.getKey().split("\u0000");
      List<Example> examples = group.getValue();
      changed += examples.size();
      changes.add(
          new Change(
              key[0],
              key[1],
              key[2],
              examples.size(),
              List.copyOf(examples.subList(0, Math.min(EXAMPLES, examples.size())))));
    }
    changes.sort((a, b) -> Integer.compare(b.count(), a.count()));
    return new Outcome.Ran(
        new Result(
            bundle.sha256(),
            firstSeq,
            lastSeq,
            decisions,
            changed,
            unchanged,
            unknowns.size(),
            changes,
            unknowns));
  }

  /**
   * Rebuilds the taint facts from an entry's flags.
   *
   * @param entry the entry
   * @return the context facts
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static CallContext.ContextFacts contextFacts(JsonNode entry) {
    Set<String> flags = new HashSet<>();
    entry.path("flags").forEach(flag -> flags.add(flag.asString()));
    return new CallContext.ContextFacts(
        flags.contains("FLOW_FROM_UNTRUSTED"), flags.contains("CONTEXT_TAINTED"));
  }

  /** Above this many rules reading redacted values, a decision is reported unknown outright. */
  private static final int MAX_UNCERTAIN_RULES = 10;

  /**
   * Decides a call whose logged arguments may be redacted. Every rule that could apply and reads a
   * redacted value is uncertain: its condition on that value may have held or not. The bundle is
   * evaluated for every combination (each uncertain rule either matching without its uncertain
   * conditions, or not matching at all); the decision is known only when every combination gives
   * the same effect.
   *
   * @param bundle the candidate
   * @param context the rebuilt call
   * @param found detectors that fired on the original arguments
   * @return the decision, or empty when it depends on a redacted value
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static Optional<Decision> decideOverRedactions(
      PolicyBundle bundle, CallContext context, Set<String> found) {
    List<Integer> uncertain = new ArrayList<>();
    for (int index = 0; index < bundle.rules().size(); index++) {
      if (!redactedConditions(bundle.rules().get(index), context).isEmpty()) {
        uncertain.add(index);
      }
    }
    if (uncertain.isEmpty()) {
      return Optional.of(ArgumentObligations.apply(PolicyEvaluator.decide(bundle, context), found));
    }
    if (uncertain.size() > MAX_UNCERTAIN_RULES) {
      return Optional.empty();
    }
    Decision first = null;
    for (int mask = 0; mask < 1 << uncertain.size(); mask++) {
      List<Rule> rules = new ArrayList<>();
      for (int index = 0; index < bundle.rules().size(); index++) {
        Rule rule = bundle.rules().get(index);
        int position = uncertain.indexOf(index);
        if (position < 0) {
          rules.add(rule);
        } else if ((mask & (1 << position)) != 0) {
          rules.add(withoutConditions(rule, redactedConditions(rule, context)));
        }
      }
      Decision decision =
          ArgumentObligations.apply(
              PolicyEvaluator.decide(new PolicyBundle(rules, List.of(), bundle.sha256()), context),
              found);
      if (first == null) {
        first = decision;
      } else if (first.effect() != decision.effect()) {
        return Optional.empty();
      }
    }
    return Optional.of(first);
  }

  /**
   * Lists a rule's argument conditions that read a redacted value, when the rule could apply.
   *
   * @param rule the rule
   * @param context the rebuilt call
   * @return the conditions, empty when the rule cannot apply or reads no redacted value
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static List<ArgCondition> redactedConditions(Rule rule, CallContext context) {
    Rule.Match match = rule.match();
    if (!match.principal().matches(context.principal())
        || !match.tool().matches(context.tool())
        || !match.context().matches(context.context())) {
      return List.of();
    }
    List<ArgCondition> redacted = new ArrayList<>();
    for (ArgCondition condition : match.args()) {
      for (JsonNode value : condition.path().select(context.arguments()).values()) {
        if (value.isString() && value.asString().contains("[REDACTED:")) {
          redacted.add(condition);
          break;
        }
      }
    }
    return redacted;
  }

  /**
   * Copies a rule without some of its argument conditions.
   *
   * @param rule the rule
   * @param removed the conditions to drop
   * @return the copy
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Rule withoutConditions(Rule rule, List<ArgCondition> removed) {
    List<ArgCondition> kept =
        rule.match().args().stream().filter(condition -> !removed.contains(condition)).toList();
    Rule.Match match =
        new Rule.Match(rule.match().principal(), rule.match().tool(), kept, rule.match().context());
    return new Rule(
        rule.id(),
        rule.description(),
        match,
        rule.effect(),
        rule.obligations(),
        rule.approval(),
        rule.reasonCode());
  }

  /**
   * Names the redacted paths the candidate's applicable rules read, for the report.
   *
   * @param bundle the candidate
   * @param context the rebuilt call
   * @return the paths, without repeats
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static List<String> redactedPaths(PolicyBundle bundle, CallContext context) {
    return bundle.rules().stream()
        .flatMap(rule -> redactedConditions(rule, context).stream())
        .map(condition -> condition.path().text())
        .distinct()
        .toList();
  }

  /**
   * Recovers which detectors fired from the redaction placeholders.
   *
   * @param arguments the redacted arguments
   * @return detector ids
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Set<String> detectors(JsonNode arguments) {
    Set<String> found = new HashSet<>();
    Matcher matcher = PLACEHOLDER.matcher(arguments.toString());
    while (matcher.find()) {
      found.add(matcher.group(1));
    }
    return found;
  }
}
