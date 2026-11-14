// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.cli;

import io.github.rnachoc.aduana.audit.ChainVerifier;
import io.github.rnachoc.aduana.audit.Checkpoints;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * `aduana audit verify|export`: check a log export offline, or fetch one from a gateway.
 *
 * <p>Credentials for a gateway come from `ADUANA_EMAIL` and `ADUANA_PASSWORD` (or the bootstrap
 * `ADUANA_ADMIN_EMAIL` and `ADUANA_ADMIN_PASSWORD`), never from the command line, so they stay out
 * of shell history.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Command(
    name = "audit",
    description = "Verify and export the audit log.",
    subcommands = {AuditCommands.Verify.class, AuditCommands.Export.class})
final class AuditCommands implements Callable<Integer> {

  private static final JsonMapper JSON = JsonMapper.builder().build();

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
    return Aduana.USAGE;
  }

  /**
   * `aduana audit verify`: verify exported files, or a live gateway's export.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Command(name = "verify", description = "Verify the hash chain and the checkpoints.")
  static final class Verify implements Callable<Integer> {

    @Option(names = "--export", description = "Entries as JSON Lines.")
    private Path export;

    @Option(
        names = "--checkpoints",
        description = "Checkpoints as JSON Lines, ideally kept elsewhere.")
    private Path checkpoints;

    @Option(names = "--key", description = "The body of /.well-known/aduana-audit-key.")
    private Path key;

    @Option(names = "--url", description = "Fetch everything from this gateway instead of files.")
    private URI url;

    @Spec private CommandLine.Model.CommandSpec spec;

    /**
     * Loads the inputs, verifies, and prints every problem and a summary.
     *
     * @return 0 when intact, 1 when tampered, 2 for usage or I/O errors
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public Integer call() {
      PrintWriter out = spec.commandLine().getOut();
      List<JsonNode> entries;
      List<JsonNode> signed;
      Optional<PublicKey> publicKey;
      try {
        if (url != null) {
          GatewayApi api = signedIn(url);
          entries = lines(api.get("/api/v1/audit/export"));
          signed = lines(api.get("/api/v1/audit/export", "checkpoints", "true"));
          publicKey = Optional.of(publicKey(api.get("/.well-known/aduana-audit-key")));
        } else if (export != null) {
          entries = lines(Files.readString(export, StandardCharsets.UTF_8));
          signed =
              checkpoints == null
                  ? List.of()
                  : lines(Files.readString(checkpoints, StandardCharsets.UTF_8));
          publicKey =
              key == null
                  ? Optional.empty()
                  : Optional.of(publicKey(Files.readString(key, StandardCharsets.UTF_8)));
        } else {
          spec.commandLine().getErr().println("give --export <file> or --url <gateway>");
          return Aduana.USAGE;
        }
      } catch (IOException | IllegalArgumentException exception) {
        spec.commandLine().getErr().println("cannot read the log: " + exception.getMessage());
        return Aduana.USAGE;
      }
      ChainVerifier.Report report = ChainVerifier.verify(entries, signed, publicKey);
      report.problems().forEach(p -> out.printf("%d: %s %s%n", p.seq(), p.kind(), p.detail()));
      out.printf(
          "%d entries, last seq %d, %d checkpoints%s: %s%n",
          report.entries(),
          report.lastSeq(),
          report.checkpoints(),
          publicKey.isPresent() ? " (signatures checked)" : " (signatures not checked)",
          report.intact() ? "intact" : report.problems().size() + " problems");
      return report.intact() ? Aduana.OK : Aduana.FAILED;
    }
  }

  /**
   * `aduana audit export`: write a gateway's entries and checkpoints to files.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Command(name = "export", description = "Export entries and checkpoints as JSON Lines.")
  static final class Export implements Callable<Integer> {

    @Option(names = "--url", required = true, description = "The gateway.")
    private URI url;

    @Option(names = "--from", description = "Only entries at or after this RFC 3339 time.")
    private String from;

    @Option(names = "--to", description = "Only entries at or before this RFC 3339 time.")
    private String to;

    @Option(names = "-o", required = true, description = "The entries file to write.")
    private Path output;

    @Option(names = "--checkpoints-out", description = "Where to write the checkpoints.")
    private Path checkpointsOutput;

    @Option(names = "--key-out", description = "Where to write the published public key.")
    private Path keyOutput;

    @Spec private CommandLine.Model.CommandSpec spec;

    /**
     * Downloads and writes the files.
     *
     * @return 0, or 2 when the gateway cannot be read or the files cannot be written
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    @Override
    public Integer call() {
      try {
        GatewayApi api = signedIn(url);
        String entries = api.get("/api/v1/audit/export", "from", from, "to", to);
        Files.writeString(output, entries, StandardCharsets.UTF_8);
        if (checkpointsOutput != null) {
          Files.writeString(
              checkpointsOutput,
              api.get("/api/v1/audit/export", "checkpoints", "true"),
              StandardCharsets.UTF_8);
        }
        if (keyOutput != null) {
          Files.writeString(
              keyOutput, api.get("/.well-known/aduana-audit-key"), StandardCharsets.UTF_8);
        }
        spec.commandLine()
            .getOut()
            .printf("wrote %d entries to %s%n", lines(entries).size(), output);
        return Aduana.OK;
      } catch (IOException exception) {
        spec.commandLine().getErr().println("export failed: " + exception.getMessage());
        return Aduana.USAGE;
      }
    }
  }

  /**
   * Signs in to a gateway with the credentials from the environment.
   *
   * @param url the gateway
   * @return a signed-in client
   * @throws IOException if the credentials are missing or refused
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static GatewayApi signedIn(URI url) throws IOException {
    String email = environment("ADUANA_EMAIL", "ADUANA_ADMIN_EMAIL");
    String password = environment("ADUANA_PASSWORD", "ADUANA_ADMIN_PASSWORD");
    if (email == null || password == null) {
      throw new IOException("set ADUANA_EMAIL and ADUANA_PASSWORD");
    }
    GatewayApi api = new GatewayApi(url);
    api.login(email, password);
    return api;
  }

  /**
   * Reads the first environment variable that is set.
   *
   * @param names the variable names, in order of preference
   * @return the value, or null when none is set
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String environment(String... names) {
    for (String name : names) {
      String value = System.getenv(name);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  /**
   * Parses JSON Lines.
   *
   * @param text the text
   * @return one node per non-empty line
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static List<JsonNode> lines(String text) {
    return text.lines().filter(line -> !line.isBlank()).map(JSON::readTree).toList();
  }

  /**
   * Reads the published key document.
   *
   * @param text the body of `/.well-known/aduana-audit-key`
   * @return the public key
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static PublicKey publicKey(String text) {
    return Checkpoints.publicKey(
        Base64.getDecoder().decode(JSON.readTree(text).path("spki").asString()));
  }
}
