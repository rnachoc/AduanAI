// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides what happens to a tool call. Pure: same bundle and context, same decision, with no clock,
 * no I/O and no randomness.
 *
 * <p>Every rule is evaluated, so a later deny always beats an earlier allow. With no matching rule
 * the decision is a deny with {@value Decision#NO_MATCHING_RULE}.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class PolicyEvaluator {

  /**
   * Prevents instances: the evaluator is a function.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private PolicyEvaluator() {}

  /**
   * Evaluates every rule and combines the matches.
   *
   * @param bundle the active policy
   * @param context the call
   * @return the decision
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static Decision decide(PolicyBundle bundle, CallContext context) {
    List<String> matched = new ArrayList<>();
    List<String> mismatches = new ArrayList<>();
    Effect strongest = null;
    Rule.Obligations obligations = Rule.Obligations.NONE;
    Optional<Rule.ApprovalSpec> approval = Optional.empty();
    Optional<String> reason = Optional.empty();
    for (Rule rule : bundle.rules()) {
      ArgCondition.Outcome outcome = matches(rule, context);
      if (outcome == ArgCondition.Outcome.TYPE_MISMATCH) {
        mismatches.add(rule.id());
      }
      if (outcome != ArgCondition.Outcome.MATCH) {
        continue;
      }
      matched.add(rule.id());
      strongest = strongest == null ? rule.effect() : strongest.strongest(rule.effect());
      if (rule.effect() == Effect.DENY) {
        reason = reason.or(() -> Optional.of(rule.reasonCode().orElse("POLICY_DENIED")));
        continue;
      }
      obligations = obligations.union(rule.obligations());
      if (rule.effect() == Effect.REQUIRE_APPROVAL) {
        approval =
            Optional.of(approval.map(a -> stricter(a, rule.approval())).orElse(rule.approval()));
      }
    }
    if (strongest == null) {
      return new Decision.Deny(Decision.NO_MATCHING_RULE, matched, mismatches);
    }
    return switch (strongest) {
      case ALLOW -> new Decision.Allow(obligations, matched, mismatches);
      case REQUIRE_APPROVAL ->
          new Decision.RequireApproval(approval.orElseThrow(), obligations, matched, mismatches);
      case DENY -> new Decision.Deny(reason.orElseThrow(), matched, mismatches);
    };
  }

  /**
   * Tells whether a tool should appear in a principal's tool list: some allow or approval rule
   * could match it, and no deny rule that ignores arguments and marks always matches it.
   *
   * @param bundle the active policy
   * @param principal the caller
   * @param tool the tool
   * @return true when the tool may be listed
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static boolean mayOffer(
      PolicyBundle bundle, CallContext.PrincipalFacts principal, CallContext.ToolFacts tool) {
    boolean reachable = false;
    for (Rule rule : bundle.rules()) {
      if (!rule.match().principal().matches(principal) || !rule.match().tool().matches(tool)) {
        continue;
      }
      boolean unconditional =
          rule.match().args().isEmpty() && rule.match().context().isUnconditional();
      if (rule.effect() == Effect.DENY && unconditional) {
        return false;
      }
      reachable |= rule.effect() != Effect.DENY;
    }
    return reachable;
  }

  /**
   * Checks every part of a rule's match; argument conditions may report a type mismatch.
   *
   * @param rule the rule
   * @param context the call
   * @return match, no match or type mismatch
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static ArgCondition.Outcome matches(Rule rule, CallContext context) {
    Rule.Match match = rule.match();
    if (!match.principal().matches(context.principal())
        || !match.tool().matches(context.tool())
        || !match.context().matches(context.context())) {
      return ArgCondition.Outcome.NO_MATCH;
    }
    for (ArgCondition condition : match.args()) {
      ArgCondition.Outcome outcome = condition.evaluate(context.arguments());
      if (outcome != ArgCondition.Outcome.MATCH) {
        return outcome;
      }
    }
    return ArgCondition.Outcome.MATCH;
  }

  /**
   * Combines two approval requirements: the first group named, the shorter expiry.
   *
   * @param first the approval of an earlier matching rule
   * @param second the approval of a later matching rule
   * @return the combined requirement
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Rule.ApprovalSpec stricter(Rule.ApprovalSpec first, Rule.ApprovalSpec second) {
    return new Rule.ApprovalSpec(
        first.approversGroup(),
        first.expiresIn().compareTo(second.expiresIn()) <= 0
            ? first.expiresIn()
            : second.expiresIn());
  }
}
