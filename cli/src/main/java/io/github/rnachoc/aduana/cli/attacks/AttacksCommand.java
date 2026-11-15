// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.cli.attacks;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * `aduana attacks`: the attack bench (plan Phase 11).
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Command(
    name = "attacks",
    description = "Run the attack bench against a gateway.",
    mixinStandardHelpOptions = true,
    subcommands = {AttacksCommand.Run.class})
public final class AttacksCommand implements Callable<Integer> {

  @Spec private CommandSpec spec;

  /**
   * Prints the usage.
   *
   * @return the usage exit code
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Override
  public Integer call() {
    spec.commandLine().usage(spec.commandLine().getErr());
    return 2;
  }

  /**
   * `aduana attacks run`: runs every case, writes the report, fails when a deterministic case was
   * not stopped.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Command(
      name = "run",
      description = "Run every case and write the report.",
      mixinStandardHelpOptions = true)
  public static final class Run implements Callable<Integer> {

    @Spec private CommandSpec spec;

    @Option(names = "--url", required = true, description = "The gateway's base URL.")
    private URI url;

    @Option(names = "--cases", defaultValue = "attacks/cases", description = "The cases directory.")
    private Path cases;

    @Option(
        names = "--report",
        defaultValue = "attacks/REPORT.md",
        description = "Where to write the report.")
    private Path report;

    @Option(
        names = "--db-url",
        required = true,
        description = "JDBC URL of the gateway's database.")
    private String databaseUrl;

    @Option(
        names = "--stub-host",
        required = true,
        description = "How the gateway reaches this runner.")
    private String stubHost;

    @Option(
        names = "--stub-port",
        defaultValue = "4567",
        description = "Port of the runner's MCP server.")
    private int stubPort;

    @Option(names = "--only", description = "Run only cases whose id starts with this.")
    private String only;

    /**
     * Runs the bench. Credentials come from `ADUANA_ADMIN_EMAIL`, `ADUANA_ADMIN_PASSWORD`,
     * `POSTGRES_USER` and `POSTGRES_PASSWORD`.
     *
     * @return 0 when every deterministic case was stopped, 1 otherwise, 2 on a usage error
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      try {
        List<AttackCase> all = AttackCase.load(cases);
        List<AttackCase> selected =
            only == null ? all : all.stream().filter(c -> c.id().startsWith(only)).toList();
        AttackRunner runner =
            new AttackRunner(
                url,
                new AttackRunner.Settings(
                    environment("ADUANA_ADMIN_EMAIL"),
                    environment("ADUANA_ADMIN_PASSWORD"),
                    databaseUrl,
                    environment("POSTGRES_USER"),
                    environment("POSTGRES_PASSWORD"),
                    stubHost,
                    stubPort));
        List<CaseResult> results = runner.run(selected, out);
        if (only == null) {
          Files.writeString(report, Report.render(results), StandardCharsets.UTF_8);
        }
        List<String> failures = Report.failures(results);
        long stopped = results.stream().filter(CaseResult::stopped).count();
        out.printf(
            "%d cases, %d stopped; deterministic not stopped: %s%n",
            results.size(), stopped, failures.isEmpty() ? "none" : failures);
        return failures.isEmpty() ? 0 : 1;
      } catch (IOException | IllegalArgumentException exception) {
        spec.commandLine().getErr().println("attack bench failed: " + exception.getMessage());
        return 2;
      }
    }

    /**
     * Reads a required environment variable.
     *
     * @param name the variable
     * @return its value
     * @throws IllegalArgumentException when it is not set
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    private static String environment(String name) {
      return Optional.ofNullable(System.getenv(name))
          .filter(value -> !value.isBlank())
          .orElseThrow(() -> new IllegalArgumentException(name + " is not set"));
    }
  }
}
