// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import static io.github.rnachoc.aduana.policy.Policies.bundle;
import static io.github.rnachoc.aduana.policy.Policies.call;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * One test per point of the semantics in plan §5.2.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
class PolicySemanticsTest {

  private static final String ALLOW_REFUND =
      """
        - id: allow-refund
          match: { tool: { server: payments, name: refund } }
          effect: allow
          obligations: { redact_result: [iban] }
      """;
  private static final String APPROVE_REFUND =
      """
        - id: approve-refund
          match: { tool: { server: payments, name: refund } }
          effect: require_approval
          approval: { approvers_group: finance, expires_in: 15m }
          obligations: { redact_result: [email] }
      """;
  private static final String DENY_BIG =
      """
        - id: deny-big
          match:
            tool: { server: payments, name: refund }
            args: [ { path: "$.amount_cents", gt: 50000 } ]
          effect: deny
          reason_code: REFUND_OVER_LIMIT
      """;

  /**
   * Builds a bundle with one condition on `$.value` of a `t/x` call.
   *
   * @param condition the condition's comparator and operand, such as `gt: 3`
   * @return the bundle
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static PolicyBundle condition(String condition) {
    return bundle(
        "  - id: c\n    match:\n      args: [ { path: \"$.value\", "
            + condition
            + " } ]\n    effect: allow\n");
  }

  /**
   * Evaluates a call to `t/x` with the given arguments.
   *
   * @param bundle the bundle
   * @param arguments the arguments as JSON
   * @return the effect
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Effect effect(PolicyBundle bundle, String arguments) {
    return PolicyEvaluator.decide(bundle, call("t", "x", arguments)).effect();
  }

  @Test
  void every_rule_is_evaluated_so_a_later_deny_beats_an_earlier_allow() {
    Decision decision =
        PolicyEvaluator.decide(
            bundle(ALLOW_REFUND + DENY_BIG),
            call("payments", "refund", "{\"amount_cents\": 60000}"));
    assertThat(decision.effect()).isEqualTo(Effect.DENY);
    assertThat(decision.matchedRules()).containsExactly("allow-refund", "deny-big");
  }

  @Test
  void a_deny_beats_a_require_approval_that_also_matches() {
    Decision decision =
        PolicyEvaluator.decide(
            bundle(APPROVE_REFUND + DENY_BIG),
            call("payments", "refund", "{\"amount_cents\": 60000}"));
    assertThat(decision).isInstanceOf(Decision.Deny.class);
    assertThat(decision.reason()).contains("REFUND_OVER_LIMIT");
  }

  @Test
  void a_require_approval_beats_an_allow_that_also_matches() {
    Decision decision =
        PolicyEvaluator.decide(
            bundle(ALLOW_REFUND + APPROVE_REFUND), call("payments", "refund", "{}"));
    assertThat(decision).isInstanceOf(Decision.RequireApproval.class);
    assertThat(((Decision.RequireApproval) decision).approval().approversGroup())
        .isEqualTo("finance");
  }

  @Test
  void a_call_no_rule_matches_is_denied_with_no_matching_rule() {
    Decision decision =
        PolicyEvaluator.decide(bundle(ALLOW_REFUND), call("crm", "get_customer", "{}"));
    assertThat(decision.reason()).contains(Decision.NO_MATCHING_RULE);
    assertThat(decision.matchedRules()).isEmpty();
  }

  @Test
  void an_empty_policy_denies_everything() {
    PolicyParser.Result parsed =
        PolicyParser.parse("version: 1\ndefaults:\n  decision: deny\nrules: []\n", Map.of());
    PolicyBundle empty = ((PolicyParser.Result.Valid) parsed).bundle();
    assertThat(PolicyEvaluator.decide(empty, call("t", "x", "{}")).effect()).isEqualTo(Effect.DENY);
  }

  @Test
  void obligations_of_every_matching_allow_and_approval_rule_are_joined() {
    Decision decision =
        PolicyEvaluator.decide(
            bundle(ALLOW_REFUND + APPROVE_REFUND), call("payments", "refund", "{}"));
    assertThat(decision.obligations().redactResult()).containsExactly("email", "iban");
  }

  @Test
  void a_deny_carries_no_obligations() {
    Decision decision =
        PolicyEvaluator.decide(
            bundle(ALLOW_REFUND + DENY_BIG),
            call("payments", "refund", "{\"amount_cents\": 99999}"));
    assertThat(decision.obligations()).isEqualTo(Rule.Obligations.NONE);
  }

  @Test
  void a_deny_without_a_reason_code_reports_policy_denied() {
    PolicyBundle bundle = bundle("  - id: no\n    match: {}\n    effect: deny\n");
    assertThat(PolicyEvaluator.decide(bundle, call("t", "x", "{}")).reason())
        .contains("POLICY_DENIED");
  }

  @Test
  void several_approval_rules_keep_the_first_group_and_the_shortest_expiry() {
    PolicyBundle bundle =
        bundle(
            APPROVE_REFUND
                + "  - id: approve-again\n    match: {}\n    effect: require_approval\n"
                + "    approval: { approvers_group: security, expires_in: 5m }\n");
    Decision.RequireApproval decision =
        (Decision.RequireApproval) PolicyEvaluator.decide(bundle, call("payments", "refund", "{}"));
    assertThat(decision.approval().approversGroup()).isEqualTo("finance");
    assertThat(decision.approval().expiresIn()).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  void an_approval_rule_without_settings_uses_the_default_group_and_fifteen_minutes() {
    Decision.RequireApproval decision =
        (Decision.RequireApproval)
            PolicyEvaluator.decide(
                bundle("  - id: a\n    match: {}\n    effect: require_approval\n"),
                call("t", "x", "{}"));
    assertThat(decision.approval()).isEqualTo(Rule.ApprovalSpec.DEFAULT);
  }

  @Test
  void eq_and_ne_compare_numbers_by_value_and_strings_exactly() {
    assertThat(effect(condition("eq: 5"), "{\"value\": 5.0}")).isEqualTo(Effect.ALLOW);
    assertThat(effect(condition("eq: \"A\""), "{\"value\": \"a\"}")).isEqualTo(Effect.DENY);
    assertThat(effect(condition("ne: \"A\""), "{\"value\": \"a\"}")).isEqualTo(Effect.ALLOW);
    assertThat(effect(condition("ne: 5"), "{\"value\": 5}")).isEqualTo(Effect.DENY);
    assertThat(effect(condition("eq: true"), "{\"value\": true}")).isEqualTo(Effect.ALLOW);
  }

  @Test
  void in_and_not_in_check_membership() {
    assertThat(effect(condition("in: [a, b]"), "{\"value\": \"b\"}")).isEqualTo(Effect.ALLOW);
    assertThat(effect(condition("in: [a, b]"), "{\"value\": \"c\"}")).isEqualTo(Effect.DENY);
    assertThat(effect(condition("not_in: [a, b]"), "{\"value\": \"c\"}")).isEqualTo(Effect.ALLOW);
    assertThat(effect(condition("not_in: [a, b]"), "{\"value\": \"a\"}")).isEqualTo(Effect.DENY);
    assertThat(effect(condition("in: [1, 2]"), "{\"value\": 2.0}")).isEqualTo(Effect.ALLOW);
  }

  @Test
  void gt_gte_lt_and_lte_compare_numbers() {
    assertThat(effect(condition("gt: 10"), "{\"value\": 11}")).isEqualTo(Effect.ALLOW);
    assertThat(effect(condition("gt: 10"), "{\"value\": 10}")).isEqualTo(Effect.DENY);
    assertThat(effect(condition("gte: 10"), "{\"value\": 10}")).isEqualTo(Effect.ALLOW);
    assertThat(effect(condition("gte: 10"), "{\"value\": 9.99}")).isEqualTo(Effect.DENY);
    assertThat(effect(condition("lt: 10"), "{\"value\": 9}")).isEqualTo(Effect.ALLOW);
    assertThat(effect(condition("lt: 10"), "{\"value\": 10}")).isEqualTo(Effect.DENY);
    assertThat(effect(condition("lte: 10"), "{\"value\": 10}")).isEqualTo(Effect.ALLOW);
    assertThat(effect(condition("lte: 10"), "{\"value\": 10.5}")).isEqualTo(Effect.DENY);
  }

  @Test
  void matches_finds_a_bounded_pattern_in_a_string() {
    assertThat(effect(condition("matches: \"^XX[0-9]+$\""), "{\"value\": \"XX123\"}"))
        .isEqualTo(Effect.ALLOW);
    assertThat(effect(condition("matches: \"^XX[0-9]+$\""), "{\"value\": \"ES123\"}"))
        .isEqualTo(Effect.DENY);
  }

  @Test
  void exists_tests_presence_and_absence() {
    assertThat(effect(condition("exists: true"), "{\"value\": null}")).isEqualTo(Effect.ALLOW);
    assertThat(effect(condition("exists: true"), "{}")).isEqualTo(Effect.DENY);
    assertThat(effect(condition("exists: false"), "{}")).isEqualTo(Effect.ALLOW);
    assertThat(effect(condition("exists: false"), "{\"value\": 1}")).isEqualTo(Effect.DENY);
  }

  @Test
  void length_lte_limits_strings_by_code_point_and_arrays_by_size() {
    assertThat(effect(condition("length_lte: 3"), "{\"value\": \"ñño\"}")).isEqualTo(Effect.ALLOW);
    assertThat(effect(condition("length_lte: 3"), "{\"value\": \"abcd\"}")).isEqualTo(Effect.DENY);
    assertThat(effect(condition("length_lte: 2"), "{\"value\": [1, 2]}")).isEqualTo(Effect.ALLOW);
    assertThat(effect(condition("length_lte: 2"), "{\"value\": [1, 2, 3]}")).isEqualTo(Effect.DENY);
  }

  @Test
  void paths_select_fields_and_indexes() {
    PolicyBundle bundle =
        bundle(
            "  - id: p\n    match:\n      args: [ { path: \"$.order.items[1].sku\", eq: B } ]\n    effect: allow\n");
    assertThat(effect(bundle, "{\"order\": {\"items\": [{\"sku\": \"A\"}, {\"sku\": \"B\"}]}}"))
        .isEqualTo(Effect.ALLOW);
    assertThat(effect(bundle, "{\"order\": {\"items\": [{\"sku\": \"A\"}]}}"))
        .isEqualTo(Effect.DENY);
  }

  @Test
  void a_wildcard_condition_must_hold_for_every_element_unless_any_is_set() {
    String all =
        "  - id: w\n    match:\n      args: [ { path: \"$.items[*]\", lt: 100 } ]\n    effect: allow\n";
    String any =
        "  - id: w\n    match:\n      args: [ { path: \"$.items[*]\", lt: 100, any: true } ]\n    effect: allow\n";
    assertThat(effect(bundle(all), "{\"items\": [1, 2, 3]}")).isEqualTo(Effect.ALLOW);
    assertThat(effect(bundle(all), "{\"items\": [1, 200]}")).isEqualTo(Effect.DENY);
    assertThat(effect(bundle(any), "{\"items\": [1, 200]}")).isEqualTo(Effect.ALLOW);
    assertThat(effect(bundle(any), "{\"items\": [300, 200]}")).isEqualTo(Effect.DENY);
  }

  @Test
  void a_path_that_selects_nothing_never_matches() {
    assertThat(effect(condition("lt: 100"), "{}")).isEqualTo(Effect.DENY);
    String all =
        "  - id: w\n    match:\n      args: [ { path: \"$.items[*]\", lt: 100 } ]\n    effect: allow\n";
    assertThat(effect(bundle(all), "{\"items\": []}")).isEqualTo(Effect.DENY);
  }

  @Test
  void a_value_of_an_unexpected_type_does_not_match_and_is_recorded_as_a_type_mismatch() {
    Decision decision =
        PolicyEvaluator.decide(condition("gt: 10"), call("t", "x", "{\"value\": \"11\"}"));
    assertThat(decision.effect()).isEqualTo(Effect.DENY);
    assertThat(decision.typeMismatches()).containsExactly("c");
    assertThat(
            PolicyEvaluator.decide(condition("matches: a"), call("t", "x", "{\"value\": 1}"))
                .typeMismatches())
        .containsExactly("c");
    assertThat(
            PolicyEvaluator.decide(condition("in: [a]"), call("t", "x", "{\"value\": 1}"))
                .typeMismatches())
        .containsExactly("c");
    assertThat(
            PolicyEvaluator.decide(condition("not_in: [a]"), call("t", "x", "{\"value\": 1}"))
                .typeMismatches())
        .containsExactly("c");
    assertThat(
            PolicyEvaluator.decide(condition("length_lte: 1"), call("t", "x", "{\"value\": 1}"))
                .typeMismatches())
        .containsExactly("c");
    assertThat(
            PolicyEvaluator.decide(condition("eq: 1"), call("t", "x", "{\"value\": \"1\"}"))
                .typeMismatches())
        .containsExactly("c");
  }

  @Test
  void principal_conditions_match_attributes_names_and_groups() {
    PolicyBundle bundle =
        bundle(
            "  - id: p\n    match:\n      principal:\n        name: { in: [agent-1] }\n"
                + "        attributes: { team: support, env: { not_in: [prod] } }\n"
                + "        groups: { any_of: [agents, robots] }\n    effect: allow\n");
    var ok =
        call(
            Map.of("team", "support", "env", "demo"),
            "t",
            "x",
            "{}",
            CallContext.ContextFacts.CLEAN);
    var prod =
        call(
            Map.of("team", "support", "env", "prod"),
            "t",
            "x",
            "{}",
            CallContext.ContextFacts.CLEAN);
    var noEnv = call(Map.of("team", "support"), "t", "x", "{}", CallContext.ContextFacts.CLEAN);
    assertThat(PolicyEvaluator.decide(bundle, ok).effect()).isEqualTo(Effect.ALLOW);
    assertThat(PolicyEvaluator.decide(bundle, prod).effect()).isEqualTo(Effect.DENY);
    assertThat(PolicyEvaluator.decide(bundle, noEnv).effect()).isEqualTo(Effect.DENY);
    PolicyBundle otherGroup =
        bundle(
            "  - id: g\n    match: { principal: { groups: { any_of: [finance] } } }\n    effect: allow\n");
    assertThat(PolicyEvaluator.decide(otherGroup, ok).effect()).isEqualTo(Effect.DENY);
    PolicyBundle otherName =
        bundle("  - id: n\n    match: { principal: { name: agent-2 } }\n    effect: allow\n");
    assertThat(PolicyEvaluator.decide(otherName, ok).effect()).isEqualTo(Effect.DENY);
  }

  @Test
  void tool_conditions_match_catalog_metadata() {
    PolicyBundle sideEffects =
        bundle(
            "  - id: s\n    match: { tool: { side_effect: true } }\n    effect: deny\n  - id: a\n    match: {}\n    effect: allow\n");
    assertThat(PolicyEvaluator.decide(sideEffects, call("payments", "refund", "{}")).effect())
        .isEqualTo(Effect.DENY);
    assertThat(PolicyEvaluator.decide(sideEffects, call("crm", "get", "{}")).effect())
        .isEqualTo(Effect.ALLOW);
    PolicyBundle untrusted =
        bundle(
            "  - id: u\n    match: { tool: { untrusted_source: true, sensitivity: { in: [low] } } }\n    effect: allow\n");
    assertThat(PolicyEvaluator.decide(untrusted, call("web", "fetch_url", "{}")).effect())
        .isEqualTo(Effect.ALLOW);
    assertThat(PolicyEvaluator.decide(untrusted, call("web", "other", "{}")).effect())
        .isEqualTo(Effect.DENY);
    PolicyBundle servers =
        bundle(
            "  - id: v\n    match: { tool: { server: { not_in: [payments] } } }\n    effect: allow\n");
    assertThat(PolicyEvaluator.decide(servers, call("payments", "x", "{}")).effect())
        .isEqualTo(Effect.DENY);
    assertThat(PolicyEvaluator.decide(servers, call("crm", "x", "{}")).effect())
        .isEqualTo(Effect.ALLOW);
  }

  @Test
  void context_conditions_match_the_data_flow_marks() {
    PolicyBundle bundle =
        bundle(
            "  - id: f\n    match: { context: { flow_from_untrusted: true, context_tainted: true } }\n    effect: require_approval\n");
    var both = call(Map.of(), "t", "x", "{}", new CallContext.ContextFacts(true, true));
    var flowOnly = call(Map.of(), "t", "x", "{}", new CallContext.ContextFacts(true, false));
    assertThat(PolicyEvaluator.decide(bundle, both).effect()).isEqualTo(Effect.REQUIRE_APPROVAL);
    assertThat(PolicyEvaluator.decide(bundle, flowOnly).effect()).isEqualTo(Effect.DENY);
  }

  @Test
  void the_evaluator_is_pure_the_same_inputs_give_the_same_decision() {
    PolicyBundle bundle = bundle(ALLOW_REFUND + APPROVE_REFUND + DENY_BIG);
    CallContext context = call("payments", "refund", "{\"amount_cents\": 100}");
    Decision first = PolicyEvaluator.decide(bundle, context);
    for (int round = 0; round < 100; round++) {
      assertThat(PolicyEvaluator.decide(bundle, context)).isEqualTo(first);
    }
  }

  @Test
  void a_tool_is_offered_only_when_an_allow_or_approval_rule_could_reach_it() {
    PolicyBundle bundle =
        bundle(
            ALLOW_REFUND
                + DENY_BIG
                + "  - id: never-crm-delete\n    match: { tool: { server: crm, name: delete } }\n    effect: deny\n"
                + "  - id: crm-all\n    match: { tool: { server: crm } }\n    effect: allow\n");
    var support = new CallContext.PrincipalFacts("a", Map.of(), Set.of());
    assertThat(
            PolicyEvaluator.mayOffer(
                bundle,
                support,
                new CallContext.ToolFacts("payments", "refund", true, false, "low")))
        .isTrue();
    assertThat(
            PolicyEvaluator.mayOffer(
                bundle, support, new CallContext.ToolFacts("crm", "delete", true, false, "low")))
        .isFalse();
    assertThat(
            PolicyEvaluator.mayOffer(
                bundle, support, new CallContext.ToolFacts("crm", "get", false, false, "low")))
        .isTrue();
    assertThat(
            PolicyEvaluator.mayOffer(
                bundle, support, new CallContext.ToolFacts("web", "fetch", false, true, "low")))
        .isFalse();
  }

  @Test
  void effects_order_from_allow_to_deny_and_keep_their_yaml_names() {
    assertThat(Effect.ALLOW.strongest(Effect.DENY)).isEqualTo(Effect.DENY);
    assertThat(Effect.DENY.strongest(Effect.REQUIRE_APPROVAL)).isEqualTo(Effect.DENY);
    assertThat(Effect.fromYaml("require_approval")).isEqualTo(Effect.REQUIRE_APPROVAL);
    assertThat(Effect.REQUIRE_APPROVAL.yamlName()).isEqualTo("require_approval");
  }
}
