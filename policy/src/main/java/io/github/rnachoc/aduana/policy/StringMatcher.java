// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import java.util.List;
import java.util.Optional;

/**
 * Matches a string against a literal, a list (`in`) or an exclusion list (`not_in`).
 *
 * <p>An absent value never matches, whatever the operator: a rule that names an attribute applies
 * only to principals that have it.
 *
 * @param operator how to compare
 * @param values the literal (one element) or the list
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record StringMatcher(Operator operator, List<String> values) {

  /**
   * The three ways a string can be matched.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public enum Operator {
    /** Equal to the single value. */
    EXACT,
    /** Equal to one of the values. */
    IN,
    /** Equal to none of the values. */
    NOT_IN
  }

  /**
   * Copies the values so the matcher cannot change.
   *
   * @param operator how to compare
   * @param values the values
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public StringMatcher {
    values = List.copyOf(values);
  }

  /**
   * Builds a literal matcher.
   *
   * @param value the literal
   * @return the matcher
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static StringMatcher exact(String value) {
    return new StringMatcher(Operator.EXACT, List.of(value));
  }

  /**
   * Tells whether a value matches.
   *
   * @param value the value, if present
   * @return true when present and accepted by the operator
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public boolean matches(Optional<String> value) {
    if (value.isEmpty()) {
      return false;
    }
    return switch (operator) {
      case EXACT, IN -> values.contains(value.get());
      case NOT_IN -> !values.contains(value.get());
    };
  }
}
