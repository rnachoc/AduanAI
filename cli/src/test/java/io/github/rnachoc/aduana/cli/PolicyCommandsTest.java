// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * `aduana policy validate|test`: exit 0 when everything passes, 1 for policy failures, 2 for usage
 * errors (plan §6.4).
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
class PolicyCommandsTest {

  private static final Path DEMO = Path.of("..", "demo", "policy");

  private final StringWriter out = new StringWriter();
  private final StringWriter err = new StringWriter();

  /**
   * Runs the command line with captured output.
   *
   * @param arguments the arguments
   * @return the exit code
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private int run(String... arguments) {
    CommandLine command = Aduana.commandLine();
    command.setOut(new PrintWriter(out, true));
    command.setErr(new PrintWriter(err, true));
    return command.execute(arguments);
  }

  @Test
  void the_demo_policy_validates_and_all_its_tests_pass() {
    assertThat(run("policy", "validate", DEMO.toString())).isZero();
    assertThat(out.toString()).contains("valid").contains("13 rules");
    assertThat(run("policy", "test", DEMO.toString())).isZero();
    assertThat(out.toString()).contains("45 passed, 0 failed");
  }

  @Test
  void an_invalid_policy_exits_1_and_says_why(@TempDir Path dir) throws IOException {
    Files.writeString(
        dir.resolve("policy.yaml"), "version: 1\ndefaults:\n  decision: allow\nrules: []\n");
    assertThat(run("policy", "validate", dir.toString())).isEqualTo(1);
    assertThat(err.toString()).contains("policy.yaml");
  }

  @Test
  void a_failing_test_case_exits_1_and_names_the_case(@TempDir Path dir) throws IOException {
    Files.writeString(
        dir.resolve("policy.yaml"), "version: 1\ndefaults: {decision: deny}\nrules: []\n");
    Files.createDirectories(dir.resolve("tests"));
    Files.writeString(
        dir.resolve("tests").resolve("t.yaml"),
        "- name: everything is allowed\n  given: { tool: { server: s, name: n } }\n  expect: { decision: allow }\n");
    assertThat(run("policy", "test", dir.toString())).isEqualTo(1);
    assertThat(out.toString())
        .contains("FAIL t.yaml: everything is allowed")
        .contains("0 passed, 1 failed");
  }

  @Test
  void a_directory_without_a_policy_is_a_usage_error(@TempDir Path dir) {
    assertThat(run("policy", "validate", dir.resolve("missing").toString())).isEqualTo(2);
    assertThat(run("policy", "nonsense")).isEqualTo(2);
  }
}
