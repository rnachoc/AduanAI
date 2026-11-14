// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import java.util.List;
import java.util.Optional;

/**
 * The evaluator's answer for one call.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public sealed interface Decision {

  /** The reason code of a deny that no rule caused. */
  String NO_MATCHING_RULE = "NO_MATCHING_RULE";

  /**
   * Returns the effect.
   *
   * @return allow, require approval or deny
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  Effect effect();

  /**
   * Returns every rule that matched, in policy order.
   *
   * @return rule ids
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  List<String> matchedRules();

  /**
   * Returns the rules whose conditions met a value of an unexpected type (`POLICY_TYPE_MISMATCH`).
   *
   * @return rule ids
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  List<String> typeMismatches();

  /**
   * The call may go ahead with these obligations.
   *
   * @param obligations what the gateway must do
   * @param matchedRules every rule that matched
   * @param typeMismatches rules that saw a type mismatch
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  record Allow(Rule.Obligations obligations, List<String> matchedRules, List<String> typeMismatches)
      implements Decision {

    /**
     * Returns the effect.
     *
     * @return allow
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public Effect effect() {
      return Effect.ALLOW;
    }
  }

  /**
   * The call needs a person's approval first.
   *
   * @param approval who approves and for how long
   * @param obligations what the gateway must do once approved
   * @param matchedRules every rule that matched
   * @param typeMismatches rules that saw a type mismatch
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  record RequireApproval(
      Rule.ApprovalSpec approval,
      Rule.Obligations obligations,
      List<String> matchedRules,
      List<String> typeMismatches)
      implements Decision {

    /**
     * Returns the effect.
     *
     * @return require approval
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public Effect effect() {
      return Effect.REQUIRE_APPROVAL;
    }
  }

  /**
   * The call must not go ahead.
   *
   * @param reasonCode the deny rule's reason code, or {@value #NO_MATCHING_RULE}
   * @param matchedRules every rule that matched
   * @param typeMismatches rules that saw a type mismatch
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  record Deny(String reasonCode, List<String> matchedRules, List<String> typeMismatches)
      implements Decision {

    /**
     * Returns the effect.
     *
     * @return deny
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public Effect effect() {
      return Effect.DENY;
    }
  }

  /**
   * Returns the obligations, which only a call that may go ahead has.
   *
   * @return the obligations, or none for a deny
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  default Rule.Obligations obligations() {
    return Rule.Obligations.NONE;
  }

  /**
   * Returns the reason code of a deny.
   *
   * @return the code, or empty for other effects
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  default Optional<String> reason() {
    return this instanceof Deny deny ? Optional.of(deny.reasonCode()) : Optional.empty();
  }
}
