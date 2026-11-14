// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.aduana.audit.Checkpoints;
import io.github.rnachoc.aduana.audit.HashChain;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * `aduana audit verify` on exported files: 0 when intact, 1 when tampered, 2 without input.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
class AuditCommandsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TENANT = "00000000-0000-0000-0000-000000000001";

  private final StringWriter out = new StringWriter();

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
    command.setErr(new PrintWriter(new StringWriter(), true));
    return command.execute(arguments);
  }

  /**
   * Writes a valid chain of entries.
   *
   * @param size how many
   * @return the entries
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static List<ObjectNode> chain(int size) {
    List<ObjectNode> entries = new ArrayList<>();
    String previous = HashChain.GENESIS;
    for (int seq = 1; seq <= size; seq++) {
      ObjectNode entry = JSON.createObjectNode();
      entry
          .put("seq", seq)
          .put("tenant_id", TENANT)
          .put("ts", "2026-11-14T10:00:0" + seq + ".000000Z");
      entry.put("type", "TOOL_CHANGED").put("prev_hash", previous);
      entry.put("hash", HashChain.hash(entry));
      previous = entry.get("hash").asString();
      entries.add(entry);
    }
    return entries;
  }

  /**
   * Writes nodes as JSON Lines.
   *
   * @param file the file
   * @param nodes the nodes
   * @throws IOException if the file cannot be written
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static void write(Path file, List<? extends Object> nodes) throws IOException {
    StringBuilder text = new StringBuilder();
    nodes.forEach(node -> text.append(node).append('\n'));
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  @Test
  void an_intact_export_with_signed_checkpoints_exits_0(@TempDir Path dir) throws IOException {
    List<ObjectNode> entries = chain(5);
    KeyPair keys = Checkpoints.generateKeyPair();
    ObjectNode checkpoint =
        Checkpoints.sign(
            keys.getPrivate(),
            "k",
            TENANT,
            5,
            entries.get(4).get("hash").asString(),
            "2026-11-14T11:00:00.000000Z");
    write(dir.resolve("entries.jsonl"), entries);
    write(dir.resolve("checkpoints.jsonl"), List.of(checkpoint));
    ObjectNode key =
        JSON.createObjectNode()
            .put("spki", Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
    Files.writeString(dir.resolve("key.json"), key.toString());
    int code =
        run(
            "audit",
            "verify",
            "--export",
            dir.resolve("entries.jsonl").toString(),
            "--checkpoints",
            dir.resolve("checkpoints.jsonl").toString(),
            "--key",
            dir.resolve("key.json").toString());
    assertThat(code).isZero();
    assertThat(out.toString())
        .contains("5 entries")
        .contains("signatures checked")
        .contains("intact");
  }

  @Test
  void a_tampered_export_exits_1_and_names_the_entry(@TempDir Path dir) throws IOException {
    List<ObjectNode> entries = chain(4);
    entries.get(2).put("type", "KEY_CREATED");
    write(dir.resolve("entries.jsonl"), entries);
    assertThat(run("audit", "verify", "--export", dir.resolve("entries.jsonl").toString()))
        .isEqualTo(1);
    assertThat(out.toString()).contains("3: HASH_MISMATCH");
  }

  @Test
  void verify_without_input_is_a_usage_error(@TempDir Path dir) {
    assertThat(run("audit", "verify")).isEqualTo(2);
    assertThat(run("audit", "verify", "--export", dir.resolve("missing.jsonl").toString()))
        .isEqualTo(2);
  }
}
