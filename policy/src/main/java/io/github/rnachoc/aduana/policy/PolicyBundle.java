// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import java.util.List;
import java.util.Objects;

/**
 * A loaded, valid policy: its rules, its test cases, and the hash that identifies it.
 *
 * @param rules the rules, in file order
 * @param tests the test cases shipped with it
 * @param sha256 lowercase hex SHA-256 of the canonical form of the policy and its tests
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record PolicyBundle(List<Rule> rules, List<PolicyTestCase> tests, String sha256) {

  /**
   * Copies the lists.
   *
   * @param rules the rules
   * @param tests the tests
   * @param sha256 the identity hash
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public PolicyBundle {
    rules = List.copyOf(rules);
    tests = List.copyOf(tests);
    Objects.requireNonNull(sha256, "sha256");
  }
}
