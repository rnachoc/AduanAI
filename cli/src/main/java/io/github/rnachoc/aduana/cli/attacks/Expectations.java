// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.cli.attacks;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Judges a step's outcome against its `expect` block. Every key present must hold.
 *
 * <ul>
 *   <li>`code`: the code is one of these; `not_code`: it is none of these
 *   <li>`status`: the HTTP status is one of these
 *   <li>`problem`: the verifier reported at least one of these kinds
 *   <li>`seen`: one of these codes appeared among repeated calls; `max_ok`: at most this many `OK`;
 *       `max_forwarded`: at most this many reached the upstream (`OK` or `FORWARDED_TOOL_ERROR`)
 *   <li>`flags`: every listed flag is present; `contains` / `not_contains`: text checks on the
 *       answer
 * </ul>
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class Expectations {

  /**
   * Not instantiated.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Expectations() {}

  /**
   * Tells whether an outcome meets an expectation.
   *
   * @param expect the expectation
   * @param outcome what happened
   * @return true when every key holds
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static boolean met(JsonNode expect, Outcome outcome) {
    boolean met = true;
    if (expect.has("code")) {
      met &= values(expect.get("code")).contains(outcome.code());
    }
    if (expect.has("not_code")) {
      met &= !values(expect.get("not_code")).contains(outcome.code());
    }
    if (expect.has("status")) {
      met &= values(expect.get("status")).contains(String.valueOf(outcome.status()));
    }
    if (expect.has("problem")) {
      met &= values(expect.get("problem")).stream().anyMatch(outcome.problems()::contains);
    }
    if (expect.has("seen")) {
      met &= values(expect.get("seen")).stream().anyMatch(outcome.codes()::contains);
    }
    if (expect.has("max_ok")) {
      met &= outcome.codes().stream().filter("OK"::equals).count() <= expect.get("max_ok").asLong();
    }
    if (expect.has("max_forwarded")) {
      met &=
          outcome.codes().stream()
                  .filter(code -> code.equals("OK") || code.equals("FORWARDED_TOOL_ERROR"))
                  .count()
              <= expect.get("max_forwarded").asLong();
    }
    if (expect.has("flags")) {
      met &= outcome.flags().containsAll(values(expect.get("flags")));
    }
    if (expect.has("contains")) {
      met &= values(expect.get("contains")).stream().allMatch(outcome.text()::contains);
    }
    if (expect.has("not_contains")) {
      met &= values(expect.get("not_contains")).stream().noneMatch(outcome.text()::contains);
    }
    return met;
  }

  /**
   * Reads one value or a list of them as strings.
   *
   * @param node a scalar or an array
   * @return the values
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static List<String> values(JsonNode node) {
    List<String> values = new ArrayList<>();
    if (node.isArray()) {
      node.forEach(value -> values.add(value.asString()));
    } else {
      values.add(node.asString());
    }
    return values;
  }
}
