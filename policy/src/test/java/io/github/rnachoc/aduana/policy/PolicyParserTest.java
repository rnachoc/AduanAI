// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import static io.github.rnachoc.aduana.policy.Policies.policy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A policy that does not validate never loads, and the reasons are reported.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
class PolicyParserTest {

  private static final String RULE = "  - id: a\n    match: {}\n    effect: allow\n";

  /**
   * Parses and returns the errors, failing if the policy was valid.
   *
   * @param policyYaml the policy
   * @param tests test files
   * @return the errors
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static List<String> errors(String policyYaml, Map<String, String> tests) {
    PolicyParser.Result result = PolicyParser.parse(policyYaml, tests);
    assertThat(result).isInstanceOf(PolicyParser.Result.Invalid.class);
    return ((PolicyParser.Result.Invalid) result).errors();
  }

  @Test
  void a_valid_policy_loads_with_a_hash_that_ignores_formatting_and_key_order() {
    String compact = policy("  - {id: a, match: {}, effect: allow}\n");
    String spread =
        "rules:\n  - effect: allow\n    id: a\n    match: {}\ndefaults: {decision: deny}\nversion: 1\n";
    var first = (PolicyParser.Result.Valid) PolicyParser.parse(compact, Map.of());
    var second = (PolicyParser.Result.Valid) PolicyParser.parse(spread, Map.of());
    assertThat(first.bundle().sha256()).hasSize(64).isEqualTo(second.bundle().sha256());
    var changed =
        (PolicyParser.Result.Valid)
            PolicyParser.parse(policy("  - {id: a, match: {}, effect: deny}\n"), Map.of());
    assertThat(changed.bundle().sha256()).isNotEqualTo(first.bundle().sha256());
  }

  @Test
  void the_tests_are_part_of_the_bundle_identity() {
    String tests =
        "- name: t\n  given: { tool: { server: s, name: n } }\n  expect: { decision: allow }\n";
    var without = (PolicyParser.Result.Valid) PolicyParser.parse(policy(RULE), Map.of());
    var with =
        (PolicyParser.Result.Valid) PolicyParser.parse(policy(RULE), Map.of("t.yaml", tests));
    assertThat(with.bundle().sha256()).isNotEqualTo(without.bundle().sha256());
    assertThat(with.bundle().tests()).hasSize(1);
  }

  @Test
  void a_policy_that_breaks_the_schema_does_not_load() {
    assertThat(
            errors(
                policy("  - id: a\n    match: {}\n    effect: allow\n    surprise: true\n"),
                Map.of()))
        .anyMatch(error -> error.contains("surprise"));
    assertThat(errors(policy("  - id: a\n    match: {}\n    effect: maybe\n"), Map.of()))
        .isNotEmpty();
    assertThat(errors("version: 2\ndefaults: {decision: deny}\nrules: []\n", Map.of()))
        .isNotEmpty();
  }

  @Test
  void a_default_other_than_deny_does_not_load() {
    assertThat(errors("version: 1\ndefaults:\n  decision: allow\nrules: []\n", Map.of()))
        .isNotEmpty();
  }

  @Test
  void duplicate_rule_ids_do_not_load() {
    assertThat(errors(policy(RULE + RULE), Map.of()))
        .anyMatch(error -> error.contains("used twice"));
  }

  @Test
  void duplicate_yaml_keys_do_not_load() {
    assertThat(errors("version: 1\nversion: 1\ndefaults: {decision: deny}\nrules: []\n", Map.of()))
        .isNotEmpty();
  }

  @Test
  void a_condition_needs_exactly_one_comparator() {
    String two =
        "  - id: a\n    match:\n      args: [ { path: \"$.x\", gt: 1, lt: 5 } ]\n    effect: allow\n";
    String none =
        "  - id: a\n    match:\n      args: [ { path: \"$.x\", any: true } ]\n    effect: allow\n";
    assertThat(errors(policy(two), Map.of()))
        .anyMatch(error -> error.contains("exactly one comparator"));
    assertThat(errors(policy(none), Map.of()))
        .anyMatch(error -> error.contains("exactly one comparator"));
  }

  @Test
  void patterns_that_do_not_compile_or_can_backtrack_without_bound_do_not_load() {
    String broken =
        "  - id: a\n    match:\n      args: [ { path: \"$.x\", matches: \"([a-z\" } ]\n    effect: allow\n";
    String backReference =
        "  - id: a\n    match:\n      args: [ { path: \"$.x\", matches: \"(a)\\\\1\" } ]\n    effect: allow\n";
    assertThat(errors(policy(broken), Map.of())).isNotEmpty();
    assertThat(errors(policy(backReference), Map.of())).isNotEmpty();
  }

  @Test
  void paths_outside_the_subset_do_not_load() {
    String filter =
        "  - id: a\n    match:\n      args: [ { path: \"$..x\", eq: 1 } ]\n    effect: allow\n";
    assertThat(errors(policy(filter), Map.of())).isNotEmpty();
    assertThatThrownBy(() -> JsonPath.parse("$[?(@.x)]"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void text_that_is_not_yaml_or_is_empty_does_not_load() {
    assertThat(errors("version: [1\n", Map.of()))
        .anyMatch(error -> error.contains("not valid YAML"));
    assertThat(errors("", Map.of())).anyMatch(error -> error.contains("empty"));
  }

  @Test
  void a_test_file_that_breaks_its_schema_stops_the_bundle() {
    assertThat(
            errors(policy(RULE), Map.of("bad.yaml", "- name: x\n  expect: { decision: allow }\n")))
        .anyMatch(error -> error.startsWith("bad.yaml"));
  }

  @Test
  void approval_durations_accept_seconds_minutes_and_hours() {
    assertThat(PolicyParser.duration("30s")).isEqualTo(Duration.ofSeconds(30));
    assertThat(PolicyParser.duration("15m")).isEqualTo(Duration.ofMinutes(15));
    assertThat(PolicyParser.duration("2h")).isEqualTo(Duration.ofHours(2));
    var parsed =
        (PolicyParser.Result.Valid)
            PolicyParser.parse(
                policy(
                    "  - id: a\n    match: {}\n    effect: require_approval\n    approval: { approvers_group: ops }\n"),
                Map.of());
    assertThat(parsed.bundle().rules().getFirst().approval())
        .isEqualTo(new Rule.ApprovalSpec("ops", Duration.ofMinutes(15)));
  }
}
