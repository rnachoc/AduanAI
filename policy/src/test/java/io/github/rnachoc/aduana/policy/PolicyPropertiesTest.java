// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generated policies and calls: adding a deny rule never relaxes a decision (CA4.2).
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
class PolicyPropertiesTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<String> TEAMS = List.of("support", "finance", "audit");
  private static final List<String> SERVERS = List.of("crm", "payments", "web");
  private static final List<String> TOOLS = List.of("get", "refund", "fetch");

  @Property(tries = 1000)
  void adding_a_deny_rule_never_relaxes_a_decision(
      @ForAll("bundles") List<Rule> rules,
      @ForAll("rules") Rule extra,
      @ForAll("calls") CallContext call) {
    Rule deny =
        new Rule(
            "extra-deny",
            "",
            extra.match(),
            Effect.DENY,
            Rule.Obligations.NONE,
            Rule.ApprovalSpec.DEFAULT,
            Optional.empty());
    Decision before = PolicyEvaluator.decide(new PolicyBundle(rules, List.of(), "a"), call);
    List<Rule> more = new ArrayList<>(rules);
    more.add(deny);
    Decision after = PolicyEvaluator.decide(new PolicyBundle(more, List.of(), "b"), call);
    assertThat(after.effect().compareTo(before.effect())).isGreaterThanOrEqualTo(0);
  }

  @Property(tries = 1000)
  void rule_order_never_changes_the_effect(
      @ForAll("bundles") List<Rule> rules, @ForAll("calls") CallContext call) {
    List<Rule> reversed = new ArrayList<>(rules);
    java.util.Collections.reverse(reversed);
    Effect forward = PolicyEvaluator.decide(new PolicyBundle(rules, List.of(), "a"), call).effect();
    Effect backward =
        PolicyEvaluator.decide(new PolicyBundle(reversed, List.of(), "b"), call).effect();
    assertThat(backward).isEqualTo(forward);
  }

  /**
   * Generates lists of up to eight rules with distinct ids.
   *
   * @return the generator
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Provide
  Arbitrary<List<Rule>> bundles() {
    return rules().list().ofMaxSize(8).map(PolicyPropertiesTest::renumber);
  }

  /**
   * Generates one rule over a small vocabulary of teams, tools and amounts.
   *
   * @return the generator
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Provide
  Arbitrary<Rule> rules() {
    Arbitrary<Optional<String>> team = Arbitraries.of(TEAMS).optional();
    Arbitrary<Optional<String>> server = Arbitraries.of(SERVERS).optional();
    Arbitrary<Optional<String>> tool = Arbitraries.of(TOOLS).optional();
    Arbitrary<Optional<Integer>> limit = Arbitraries.integers().between(0, 1000).optional();
    Arbitrary<Effect> effect = Arbitraries.of(Effect.class);
    Arbitrary<Optional<Boolean>> flow = Arbitraries.of(true, false).optional();
    return Combinators.combine(team, server, tool, limit, effect, flow)
        .as((t, s, n, l, e, f) -> {
          Rule.PrincipalMatch principal =
              new Rule.PrincipalMatch(
                  Optional.empty(),
                  t.map(v -> Map.of("team", StringMatcher.exact(v))).orElse(Map.of()),
                  List.of());
          Rule.ToolMatch toolMatch =
              new Rule.ToolMatch(
                  s.map(StringMatcher::exact),
                  n.map(StringMatcher::exact),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty());
          List<ArgCondition> args =
              l.map(v ->
                      List.of(
                          new ArgCondition(
                              JsonPath.parse("$.amount"),
                              ArgCondition.Comparator.GT,
                              JSON.getNodeFactory().numberNode(v),
                              Optional.empty(),
                              false)))
                  .orElse(List.of());
          Rule.ContextMatch context = new Rule.ContextMatch(f, Optional.empty());
          return new Rule(
              "r",
              "",
              new Rule.Match(principal, toolMatch, args, context),
              e,
              Rule.Obligations.NONE,
              new Rule.ApprovalSpec("g", Duration.ofMinutes(1)),
              Optional.empty());
        });
  }

  /**
   * Generates calls over the same vocabulary.
   *
   * @return the generator
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Provide
  Arbitrary<CallContext> calls() {
    return Combinators.combine(
            Arbitraries.of(TEAMS),
            Arbitraries.of(SERVERS),
            Arbitraries.of(TOOLS),
            Arbitraries.integers().between(0, 1000),
            Arbitraries.of(true, false))
        .as((team, server, tool, amount, flow) ->
            new CallContext(
                new CallContext.PrincipalFacts("p", Map.of("team", team), Set.of()),
                new CallContext.ToolFacts(server, tool, false, false, "low"),
                JSON.createObjectNode().put("amount", amount),
                new CallContext.ContextFacts(flow, false)));
  }

  /**
   * Gives generated rules distinct ids.
   *
   * @param rules the generated rules
   * @return the same rules with ids r0, r1, …
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static List<Rule> renumber(List<Rule> rules) {
    List<Rule> numbered = new ArrayList<>();
    for (int index = 0; index < rules.size(); index++) {
      Rule rule = rules.get(index);
      numbered.add(
          new Rule(
              "r" + index,
              "",
              rule.match(),
              rule.effect(),
              rule.obligations(),
              rule.approval(),
              rule.reasonCode()));
    }
    return numbered;
  }
}
