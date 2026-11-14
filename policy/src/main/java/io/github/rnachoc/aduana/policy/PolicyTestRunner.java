// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Runs the test cases shipped with a policy against that policy.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class PolicyTestRunner {

  /**
   * The result of one case.
   *
   * @param file the test file
   * @param name the case name
   * @param problems what differed from the expectation; empty when the case passed
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Outcome(String file, String name, List<String> problems) {

    /**
     * Tells whether the case passed.
     *
     * @return true when nothing differed
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public boolean passed() {
      return problems.isEmpty();
    }
  }

  /**
   * Prevents instances: the runner is a function.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private PolicyTestRunner() {}

  /**
   * Runs every case of a bundle.
   *
   * @param bundle the bundle
   * @return one outcome per case, in file and case order
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static List<Outcome> run(PolicyBundle bundle) {
    List<Outcome> outcomes = new ArrayList<>();
    for (PolicyTestCase test : bundle.tests()) {
      outcomes.add(new Outcome(test.file(), test.name(), check(bundle, test)));
    }
    return outcomes;
  }

  /**
   * Compares one decision with its expectation.
   *
   * @param bundle the bundle
   * @param test the case
   * @return the differences
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static List<String> check(PolicyBundle bundle, PolicyTestCase test) {
    Decision decision = PolicyEvaluator.decide(bundle, test.given());
    List<String> problems = new ArrayList<>();
    if (decision.effect() != test.expectedEffect()) {
      problems.add(
          "expected " + test.expectedEffect().yamlName() + ", got " + decision.effect().yamlName());
    }
    test.expectedRules()
        .filter(rules -> !new HashSet<>(rules).equals(new HashSet<>(decision.matchedRules())))
        .ifPresent(
            rules -> problems.add("expected rules " + rules + ", got " + decision.matchedRules()));
    test.expectedReason()
        .filter(reason -> !decision.reason().map(reason::equals).orElse(false))
        .ifPresent(reason ->
            problems.add(
                "expected reason " + reason + ", got " + decision.reason().orElse("none")));
    test.expectedRedactions()
        .filter(redactions -> !redactions.equals(decision.obligations().redactResult()))
        .ifPresent(redactions ->
            problems.add(
                "expected redactions "
                    + redactions
                    + ", got "
                    + decision.obligations().redactResult()));
    return problems;
  }
}
