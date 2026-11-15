// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.cli.attacks;

import java.util.List;

/**
 * What one step observed.
 *
 * @param code the agent-facing code (`OK`, `POLICY_DENIED`, `RPC_-32602`, `HTTP_401`, `SQL_ERROR`…)
 * @param status the HTTP status, or 0
 * @param problems problem kinds the verifier found, for a tampering step
 * @param codes every code seen, for repeated or parallel calls
 * @param flags flags of the result or log entry
 * @param text the raw answer, for `contains` checks
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record Outcome(
    String code,
    int status,
    List<String> problems,
    List<String> codes,
    List<String> flags,
    String text) {

  /**
   * An outcome that is only a code.
   *
   * @param code the code
   * @return the outcome
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static Outcome code(String code) {
    return new Outcome(code, 0, List.of(), List.of(code), List.of(), "");
  }

  /**
   * An outcome that is only an HTTP status.
   *
   * @param status the status
   * @return the outcome
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static Outcome status(int status) {
    return new Outcome(
        "HTTP_" + status, status, List.of(), List.of("HTTP_" + status), List.of(), "");
  }

  /**
   * An outcome of a verification.
   *
   * @param problems the problem kinds found
   * @return the outcome
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static Outcome problems(List<String> problems) {
    return new Outcome(
        problems.isEmpty() ? "INTACT" : "TAMPERING_DETECTED",
        0,
        List.copyOf(problems),
        List.of(),
        List.of(),
        "");
  }

  /**
   * Describes the outcome for the report, without anything that changes between runs.
   *
   * @return a short description
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public String describe() {
    if (!problems.isEmpty()) {
      return String.join(", ", problems.stream().distinct().sorted().toList());
    }
    if (codes.size() > 1) {
      return codes.stream()
          .distinct()
          .sorted()
          .map(c -> c + " x" + codes.stream().filter(c::equals).count())
          .reduce((a, b) -> a + ", " + b)
          .orElse(code);
    }
    return code;
  }
}
