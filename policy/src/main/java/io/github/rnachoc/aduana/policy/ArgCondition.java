// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * One condition on the call's arguments: a path, a comparator and an operand.
 *
 * <p>Through `[*]` a condition must hold for every selected element, or for at least one when `any`
 * is set. A path that selects nothing never matches, except `exists: false`. A value of an
 * unexpected type does not match and is reported as a type mismatch.
 *
 * @param path where to look
 * @param comparator how to compare
 * @param operand the value compared against (unused by `exists`)
 * @param regex the compiled pattern for `matches`
 * @param any whether one wildcard element is enough
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record ArgCondition(
    JsonPath path,
    Comparator comparator,
    JsonNode operand,
    Optional<BoundedRegex> regex,
    boolean any) {

  /**
   * The comparators plan §5.2 allows.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public enum Comparator {
    /** Equal (numbers compare by value). */
    EQ,
    /** Not equal. */
    NE,
    /** Equal to one of a list. */
    IN,
    /** Equal to none of a list. */
    NOT_IN,
    /** Greater than a number. */
    GT,
    /** Greater than or equal to a number. */
    GTE,
    /** Less than a number. */
    LT,
    /** Less than or equal to a number. */
    LTE,
    /** A string in which a bounded pattern finds a match. */
    MATCHES,
    /** Present (true) or absent (false). */
    EXISTS,
    /** A string or array of at most this length. */
    LENGTH_LTE;

    /**
     * Returns the spelling used in policy files.
     *
     * @return such as `not_in` or `length_lte`
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public String yamlName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * The outcome of checking one condition.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public enum Outcome {
    /** The condition holds. */
    MATCH,
    /** The condition does not hold. */
    NO_MATCH,
    /** A value had a type the comparator cannot compare; treated as no match. */
    TYPE_MISMATCH
  }

  /**
   * Requires the parts a comparator needs.
   *
   * @param path where to look
   * @param comparator how to compare
   * @param operand the operand
   * @param regex the pattern for `matches`
   * @param any whether one element is enough
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ArgCondition {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(comparator, "comparator");
    Objects.requireNonNull(operand, "operand");
    if (comparator == Comparator.MATCHES && regex.isEmpty()) {
      throw new IllegalArgumentException("matches needs a pattern");
    }
  }

  /**
   * Checks the condition against a call's arguments.
   *
   * @param arguments the arguments object
   * @return match, no match or type mismatch
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Outcome evaluate(JsonNode arguments) {
    JsonPath.Selection selection = path.select(arguments);
    List<JsonNode> values = selection.values();
    if (comparator == Comparator.EXISTS) {
      return values.isEmpty() != operand.asBoolean() ? Outcome.MATCH : Outcome.NO_MATCH;
    }
    if (values.isEmpty()) {
      return Outcome.NO_MATCH;
    }
    boolean mismatch = false;
    int matches = 0;
    for (JsonNode value : values) {
      Outcome outcome = compare(value);
      mismatch |= outcome == Outcome.TYPE_MISMATCH;
      matches += outcome == Outcome.MATCH ? 1 : 0;
    }
    boolean holds = any && selection.wildcard() ? matches > 0 : matches == values.size();
    if (holds) {
      return Outcome.MATCH;
    }
    return mismatch ? Outcome.TYPE_MISMATCH : Outcome.NO_MATCH;
  }

  /**
   * Compares one selected value.
   *
   * @param value the value
   * @return the outcome for this value
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Outcome compare(JsonNode value) {
    return switch (comparator) {
      case EQ -> sameKind(value, operand) ? result(equal(value, operand)) : Outcome.TYPE_MISMATCH;
      case NE -> sameKind(value, operand) ? result(!equal(value, operand)) : Outcome.TYPE_MISMATCH;
      case IN -> member(value);
      case NOT_IN -> negate(member(value));
      case GT, GTE, LT, LTE -> numeric(value);
      case MATCHES ->
          value.isString()
              ? result(regex.orElseThrow().find(value.asString()))
              : Outcome.TYPE_MISMATCH;
      case LENGTH_LTE -> length(value);
      case EXISTS -> throw new IllegalStateException("exists is handled before comparison");
    };
  }

  /**
   * Checks list membership; values of a kind absent from the list are a type mismatch.
   *
   * @param value the value
   * @return the outcome
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Outcome member(JsonNode value) {
    boolean comparable = false;
    for (JsonNode candidate : operand) {
      if (sameKind(value, candidate)) {
        comparable = true;
        if (equal(value, candidate)) {
          return Outcome.MATCH;
        }
      }
    }
    return comparable ? Outcome.NO_MATCH : Outcome.TYPE_MISMATCH;
  }

  /**
   * Compares a number with the operand.
   *
   * @param value the value
   * @return the outcome
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Outcome numeric(JsonNode value) {
    if (!value.isNumber()) {
      return Outcome.TYPE_MISMATCH;
    }
    int order = value.decimalValue().compareTo(operand.decimalValue());
    return result(
        switch (comparator) {
          case GT -> order > 0;
          case GTE -> order >= 0;
          case LT -> order < 0;
          default -> order <= 0;
        });
  }

  /**
   * Checks the length of a string (in code points) or an array.
   *
   * @param value the value
   * @return the outcome
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Outcome length(JsonNode value) {
    long limit = operand.asLong();
    if (value.isString()) {
      return result(value.asString().codePointCount(0, value.asString().length()) <= limit);
    }
    if (value.isArray()) {
      return result(value.size() <= limit);
    }
    return Outcome.TYPE_MISMATCH;
  }

  /**
   * Tells whether two values can be compared for equality.
   *
   * @param a one value
   * @param b another value
   * @return true for two strings, two numbers or two booleans
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static boolean sameKind(JsonNode a, JsonNode b) {
    return (a.isString() && b.isString())
        || (a.isNumber() && b.isNumber())
        || (a.isBoolean() && b.isBoolean());
  }

  /**
   * Compares two values of the same kind; numbers by value, so 1 equals 1.0.
   *
   * @param a one value
   * @param b another value
   * @return true when equal
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static boolean equal(JsonNode a, JsonNode b) {
    if (a.isNumber()) {
      BigDecimal left = a.decimalValue();
      return left.compareTo(b.decimalValue()) == 0;
    }
    return a.equals(b);
  }

  /**
   * Turns a boolean into an outcome.
   *
   * @param holds whether the comparison holds
   * @return match or no match
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Outcome result(boolean holds) {
    return holds ? Outcome.MATCH : Outcome.NO_MATCH;
  }

  /**
   * Inverts a match, keeping a type mismatch as it is.
   *
   * @param outcome the outcome
   * @return the inverted outcome
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Outcome negate(Outcome outcome) {
    return switch (outcome) {
      case MATCH -> Outcome.NO_MATCH;
      case NO_MATCH -> Outcome.MATCH;
      case TYPE_MISMATCH -> Outcome.TYPE_MISMATCH;
    };
  }
}
