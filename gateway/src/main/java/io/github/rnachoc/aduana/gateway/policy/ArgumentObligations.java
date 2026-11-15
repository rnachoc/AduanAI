// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.policy;

import io.github.rnachoc.aduana.policy.Decision;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Applies `deny_args_containing` to a decision, the same way for live calls and for simulations.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class ArgumentObligations {

  /**
   * Not instantiated.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ArgumentObligations() {}

  /**
   * Turns an allow or an approval into a deny when the arguments contain a value of a detector the
   * matching rules list under `deny_args_containing`.
   *
   * @param decision the policy's decision
   * @param found detectors that fired on the arguments
   * @return the decision to apply
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static Decision apply(Decision decision, Set<String> found) {
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
}
