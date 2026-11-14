// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.cli;

import io.github.rnachoc.aduana.policy.PolicyBundle;
import io.github.rnachoc.aduana.policy.PolicyParser;
import io.github.rnachoc.aduana.policy.PolicyTestRunner;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * The `aduana` command. Exit codes (plan §6.4): 0 when everything passes, 1 when a policy or a
 * verification fails, 2 for a usage error.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Command(
    name = "aduana",
    mixinStandardHelpOptions = true,
    version = "aduana 0.1.0",
    subcommands = {Aduana.Policy.class},
    description = "Check Aduana policies and logs.")
public final class Aduana implements Callable<Integer> {

  /** Everything passed. */
  static final int OK = 0;

  /** A policy, a test case or a verification failed. */
  static final int FAILED = 1;

  /** The command was used wrongly. */
  static final int USAGE = 2;

  @Spec private CommandLine.Model.CommandSpec spec;

  /**
   * Runs the command line and exits with its code.
   *
   * @param arguments the arguments
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static void main(String[] arguments) {
    System.exit(commandLine().execute(arguments));
  }

  /**
   * Builds the command line with usage errors mapped to exit code 2.
   *
   * @return the command line
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static CommandLine commandLine() {
    CommandLine command = new CommandLine(new Aduana());
    command.setParameterExceptionHandler((exception, arguments) -> {
      exception.getCommandLine().getErr().println(exception.getMessage());
      return USAGE;
    });
    return command;
  }

  /**
   * Without a subcommand, prints usage and reports a usage error.
   *
   * @return 2
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Override
  public Integer call() {
    spec.commandLine().usage(spec.commandLine().getErr());
    return USAGE;
  }

  /**
   * `aduana policy …`.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Command(
      name = "policy",
      description = "Validate and test policy bundles.",
      subcommands = {Validate.class, Test.class})
  static final class Policy implements Callable<Integer> {

    @Spec private CommandLine.Model.CommandSpec spec;

    /**
     * Without a subcommand, prints usage and reports a usage error.
     *
     * @return 2
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public Integer call() {
      spec.commandLine().usage(spec.commandLine().getErr());
      return USAGE;
    }
  }

  /**
   * `aduana policy validate <dir>`: parse and check the bundle without running its tests.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Command(name = "validate", description = "Check that a bundle loads.")
  static final class Validate implements Callable<Integer> {

    @Parameters(index = "0", description = "Bundle directory with policy.yaml and tests/.")
    private Path directory;

    @Spec private CommandLine.Model.CommandSpec spec;

    /**
     * Validates the bundle.
     *
     * @return the exit code
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public Integer call() {
      Loaded loaded = load(spec, directory);
      loaded
          .bundle()
          .ifPresent(bundle ->
              spec.commandLine()
                  .getOut()
                  .printf(
                      "valid: %d rules, %d tests, sha256 %s%n",
                      bundle.rules().size(), bundle.tests().size(), bundle.sha256()));
      return loaded.exit();
    }
  }

  /**
   * `aduana policy test <dir>`: load the bundle and run every test case.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Command(name = "test", description = "Run a bundle's test cases.")
  static final class Test implements Callable<Integer> {

    @Parameters(index = "0", description = "Bundle directory with policy.yaml and tests/.")
    private Path directory;

    @Spec private CommandLine.Model.CommandSpec spec;

    /**
     * Runs the bundle's tests and prints one line per failure and a summary.
     *
     * @return the exit code
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public Integer call() {
      Loaded loaded = load(spec, directory);
      if (loaded.bundle().isEmpty()) {
        return loaded.exit();
      }
      List<PolicyTestRunner.Outcome> outcomes = PolicyTestRunner.run(loaded.bundle().get());
      var out = spec.commandLine().getOut();
      long failed = outcomes.stream().filter(outcome -> !outcome.passed()).count();
      for (PolicyTestRunner.Outcome outcome : outcomes) {
        if (!outcome.passed()) {
          out.printf("FAIL %s: %s%n", outcome.file(), outcome.name());
          outcome.problems().forEach(problem -> out.printf("     %s%n", problem));
        }
      }
      out.printf("%d passed, %d failed%n", outcomes.size() - failed, failed);
      return failed == 0 ? OK : FAILED;
    }
  }

  /**
   * A bundle that loaded, or the exit code that says why it did not.
   *
   * @param bundle the bundle, if it loaded
   * @param exit 0 when it loaded, 1 when it is invalid, 2 when it could not be read
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  record Loaded(Optional<PolicyBundle> bundle, int exit) {}

  /**
   * Reads and parses a bundle, printing errors.
   *
   * @param spec the running command, for its streams
   * @param directory the bundle directory
   * @return the bundle, or the exit code after printing why it did not load
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Loaded load(CommandLine.Model.CommandSpec spec, Path directory) {
    BundleFiles files;
    try {
      files = BundleFiles.read(directory);
    } catch (IOException exception) {
      spec.commandLine()
          .getErr()
          .println("cannot read " + directory + ": " + exception.getMessage());
      return new Loaded(Optional.empty(), USAGE);
    }
    PolicyParser.Result result = PolicyParser.parse(files.policy(), files.tests());
    if (result instanceof PolicyParser.Result.Invalid(List<String> errors)) {
      errors.forEach(spec.commandLine().getErr()::println);
      return new Loaded(Optional.empty(), FAILED);
    }
    return new Loaded(Optional.of(((PolicyParser.Result.Valid) result).bundle()), OK);
  }
}
