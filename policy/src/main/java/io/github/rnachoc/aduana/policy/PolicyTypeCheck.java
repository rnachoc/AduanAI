// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Catches type errors at load time where the tool's input schema makes them knowable: a rule that
 * names one exact tool and compares a top-level field whose schema type cannot satisfy the
 * comparator, such as `gt` on a string field.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class PolicyTypeCheck {

  private static final Set<ArgCondition.Comparator> NUMERIC =
      Set.of(
          ArgCondition.Comparator.GT,
          ArgCondition.Comparator.GTE,
          ArgCondition.Comparator.LT,
          ArgCondition.Comparator.LTE);

  /**
   * Prevents instances: the check is a function.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private PolicyTypeCheck() {}

  /**
   * Lists every condition that can never be type-correct for the tool it names.
   *
   * @param bundle the bundle
   * @param fieldTypes for each `server__tool`, the JSON Schema `type` of each top-level property
   * @return errors; empty when nothing is known to be wrong
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static List<String> check(
      PolicyBundle bundle, Map<String, Map<String, String>> fieldTypes) {
    List<String> errors = new ArrayList<>();
    for (Rule rule : bundle.rules()) {
      Optional<String> tool = exactTool(rule.match().tool());
      if (tool.isEmpty() || !fieldTypes.containsKey(tool.get())) {
        continue;
      }
      for (ArgCondition condition : rule.match().args()) {
        if (condition.path().segments().size() != 1
            || condition.path().segments().getFirst().wildcard()) {
          continue;
        }
        String field = condition.path().segments().getFirst().field();
        String type = fieldTypes.get(tool.get()).get(field);
        if (type != null && !compatible(condition, type)) {
          errors.add(
              "rule "
                  + rule.id()
                  + ": "
                  + condition.comparator().yamlName()
                  + " on "
                  + condition.path().text()
                  + " cannot apply to a "
                  + type
                  + " field of "
                  + tool.get());
        }
      }
    }
    return errors;
  }

  /**
   * Returns the one tool a match names exactly, as `server__tool`.
   *
   * @param match the tool condition
   * @return the qualified name, when both parts are literals
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Optional<String> exactTool(Rule.ToolMatch match) {
    if (match.server().isEmpty() || match.name().isEmpty()) {
      return Optional.empty();
    }
    StringMatcher server = match.server().get();
    StringMatcher name = match.name().get();
    if (server.operator() != StringMatcher.Operator.EXACT
        || name.operator() != StringMatcher.Operator.EXACT) {
      return Optional.empty();
    }
    return Optional.of(server.values().getFirst() + "__" + name.values().getFirst());
  }

  /**
   * Tells whether a comparator can ever hold for a field of a schema type.
   *
   * @param condition the condition
   * @param type the JSON Schema type
   * @return false when the combination is always a type mismatch
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static boolean compatible(ArgCondition condition, String type) {
    boolean number = "number".equals(type) || "integer".equals(type);
    if (NUMERIC.contains(condition.comparator())) {
      return number;
    }
    return switch (condition.comparator()) {
      case MATCHES -> "string".equals(type);
      case LENGTH_LTE -> "string".equals(type) || "array".equals(type);
      case EQ, NE -> operandFits(condition.operand(), type);
      case IN, NOT_IN -> {
        for (var value : condition.operand()) {
          if (operandFits(value, type)) {
            yield true;
          }
        }
        yield false;
      }
      default -> true;
    };
  }

  /**
   * Tells whether a literal can equal a value of a schema type.
   *
   * @param operand the literal
   * @param type the JSON Schema type
   * @return true when the kinds agree
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static boolean operandFits(tools.jackson.databind.JsonNode operand, String type) {
    return switch (type) {
      case "string" -> operand.isString();
      case "number", "integer" -> operand.isNumber();
      case "boolean" -> operand.isBoolean();
      default -> true;
    };
  }
}
