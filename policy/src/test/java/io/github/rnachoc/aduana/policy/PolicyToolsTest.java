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
 * The test runner, the load-time type check and the bounded regular expressions.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
class PolicyToolsTest {

  private static final String RULES =
      """
        - id: refunds-need-a-person
          match: { tool: { server: payments, name: refund } }
          effect: require_approval
          obligations: { redact_result: [iban] }
        - id: big-refunds-denied
          match:
            tool: { server: payments, name: refund }
            args: [ { path: "$.amount_cents", gt: 50000 } ]
          effect: deny
          reason_code: REFUND_OVER_LIMIT
      """;

  /**
   * Parses the shared rules with one test file.
   *
   * @param tests the test file text
   * @return the bundle
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static PolicyBundle withTests(String tests) {
    return ((PolicyParser.Result.Valid)
            PolicyParser.parse(policy(RULES), Map.of("cases.yaml", tests)))
        .bundle();
  }

  @Test
  void passing_cases_pass_and_each_kind_of_difference_is_reported() {
    String tests =
        """
        - name: a support agent cannot refund on its own
          given:
            principal: { attributes: { team: support } }
            tool: { server: payments, name: refund }
            args: { amount_cents: 1000, order_id: "A1" }
          expect: { decision: require_approval, rules: [refunds-need-a-person], redact_result: [iban] }
        - name: big refunds are denied
          given:
            tool: { server: payments, name: refund }
            args: { amount_cents: 60000 }
          expect: { decision: deny, reason_code: REFUND_OVER_LIMIT }
        - name: wrong effect
          given: { tool: { server: payments, name: refund } }
          expect: { decision: allow }
        - name: wrong rules
          given: { tool: { server: payments, name: refund } }
          expect: { decision: require_approval, rules: [big-refunds-denied] }
        - name: wrong reason
          given: { tool: { server: payments, name: refund }, args: { amount_cents: 60000 } }
          expect: { decision: deny, reason_code: OTHER }
        - name: wrong redactions
          given: { tool: { server: payments, name: refund } }
          expect: { decision: require_approval, redact_result: [email] }
        """;
    List<PolicyTestRunner.Outcome> outcomes = PolicyTestRunner.run(withTests(tests));
    assertThat(outcomes).hasSize(6);
    assertThat(outcomes.get(0).passed()).isTrue();
    assertThat(outcomes.get(1).passed()).isTrue();
    assertThat(outcomes.get(2).problems()).anyMatch(p -> p.contains("expected allow"));
    assertThat(outcomes.get(3).problems()).anyMatch(p -> p.contains("expected rules"));
    assertThat(outcomes.get(4).problems()).anyMatch(p -> p.contains("expected reason"));
    assertThat(outcomes.get(5).problems()).anyMatch(p -> p.contains("expected redactions"));
    assertThat(outcomes.get(2).file()).isEqualTo("cases.yaml");
  }

  @Test
  void a_comparator_that_the_tool_schema_makes_impossible_is_a_load_error() {
    PolicyBundle bundle = withTests("[]");
    assertThat(
            PolicyTypeCheck.check(
                bundle, Map.of("payments__refund", Map.of("amount_cents", "integer"))))
        .isEmpty();
    assertThat(
            PolicyTypeCheck.check(
                bundle, Map.of("payments__refund", Map.of("amount_cents", "string"))))
        .singleElement()
        .asString()
        .contains("big-refunds-denied");
  }

  @Test
  void the_type_check_covers_every_comparator_kind_and_skips_what_it_cannot_know() {
    String rules =
        """
          - id: m
            match:
              tool: { server: s, name: t }
              args:
                - { path: "$.a", matches: "x" }
                - { path: "$.b", length_lte: 3 }
                - { path: "$.c", eq: 1 }
                - { path: "$.d", in: [x, y] }
                - { path: "$.e", exists: true }
                - { path: "$.f[*]", gt: 1 }
            effect: allow
          - id: loose
            match:
              tool: { server: { in: [s] }, name: t }
              args: [ { path: "$.a", gt: 1 } ]
            effect: allow
        """;
    PolicyBundle bundle =
        ((PolicyParser.Result.Valid) PolicyParser.parse(policy(rules), Map.of())).bundle();
    Map<String, String> wrong =
        Map.of("a", "number", "b", "boolean", "c", "string", "d", "number", "e", "string");
    assertThat(PolicyTypeCheck.check(bundle, Map.of("s__t", wrong))).hasSize(4);
    Map<String, String> right =
        Map.of("a", "string", "b", "array", "c", "integer", "d", "string", "e", "number");
    assertThat(PolicyTypeCheck.check(bundle, Map.of("s__t", right))).isEmpty();
    assertThat(PolicyTypeCheck.check(bundle, Map.of())).isEmpty();
  }

  @Test
  void a_catastrophic_pattern_gives_up_quickly_instead_of_hanging() {
    BoundedRegex regex = new BoundedRegex("^(a+)+$");
    long start = System.nanoTime();
    assertThat(regex.find("a".repeat(40) + "!")).isFalse();
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    assertThat(regex.find("aaaa")).isTrue();
    assertThat(regex.find("a".repeat(BoundedRegex.MAX_INPUT + 1))).isFalse();
    assertThat(regex.toString()).isEqualTo("^(a+)+$");
  }

  @Test
  void look_around_and_back_references_are_refused() {
    assertThatThrownBy(() -> new BoundedRegex("(?=a)"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BoundedRegex("(?<!a)b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BoundedRegex("(?<n>a)\\k<n>"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void string_matchers_never_match_an_absent_value() {
    StringMatcher notIn = new StringMatcher(StringMatcher.Operator.NOT_IN, List.of("a"));
    assertThat(notIn.matches(java.util.Optional.empty())).isFalse();
    assertThat(notIn.matches(java.util.Optional.of("b"))).isTrue();
  }
}
