// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.cli.attacks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * One attack against the demo stack, as written in `attacks/cases/*.yaml`.
 *
 * @param id a stable identifier, such as `INJ-03`
 * @param category one of {@link #MINIMUMS}
 * @param title what the attacker tries, in one line
 * @param defence what should stop it
 * @param deterministic true when the defence is a rule that always applies; false for heuristics
 *     (injection patterns, taint by literal value), whose rate is reported as measured
 * @param steps the steps, each an object whose first key names its kind
 * @param file the file the case came from
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record AttackCase(
    String id,
    String category,
    String title,
    String defence,
    boolean deterministic,
    List<JsonNode> steps,
    String file) {

  /** Plan Phase 11: the categories and the least number of cases in each. */
  public static final Map<String, Long> MINIMUMS = minimums();

  /** The step kinds the runner knows. */
  public static final Set<String> STEPS =
      Set.of(
          "agent",
          "call",
          "raw",
          "http",
          "approve",
          "reject",
          "sql",
          "tamper",
          "log",
          "stub",
          "stub-change",
          "list",
          "parallel",
          "repeat",
          "wait");

  private static final YAMLMapper YAML = YAMLMapper.builder().build();

  /**
   * Builds the table of minimums in the plan's order.
   *
   * @return category to minimum
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Map<String, Long> minimums() {
    Map<String, Long> minimums = new LinkedHashMap<>();
    minimums.put("tool-result-injection", 12L);
    minimums.put("tool-description-poisoning", 6L);
    minimums.put("argument-exfiltration", 10L);
    minimums.put("confused-deputy", 8L);
    minimums.put("off-catalog-escalation", 6L);
    minimums.put("approval-reuse", 8L);
    minimums.put("log-tampering", 8L);
    minimums.put("resource-abuse", 8L);
    minimums.put("redaction-evasion", 8L);
    minimums.put("admin-routes", 6L);
    return Map.copyOf(minimums);
  }

  /**
   * Loads every case in a directory. A file holds one case, or a `cases:` list of them.
   *
   * @param directory the directory
   * @return the cases, in file and then list order
   * @throws IOException if a file cannot be read
   * @throws IllegalArgumentException if a case misses a field or uses an unknown step
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static List<AttackCase> load(Path directory) throws IOException {
    List<Path> files;
    try (Stream<Path> listing = Files.list(directory)) {
      files = listing.filter(path -> path.toString().endsWith(".yaml")).sorted().toList();
    }
    List<AttackCase> cases = new ArrayList<>();
    for (Path file : files) {
      JsonNode root = YAML.readTree(Files.readString(file));
      List<JsonNode> nodes = new ArrayList<>();
      if (root.has("cases")) {
        root.get("cases").forEach(nodes::add);
      } else {
        nodes.add(root);
      }
      for (JsonNode node : nodes) {
        cases.add(parse(node, file.getFileName().toString()));
      }
    }
    return cases;
  }

  /**
   * Reads one case and checks it.
   *
   * @param node the case
   * @param file its file name
   * @return the case
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static AttackCase parse(JsonNode node, String file) {
    String id = node.path("id").asString("");
    for (String field : List.of("id", "category", "title", "defence", "deterministic", "steps")) {
      if (!node.has(field)) {
        throw new IllegalArgumentException(file + " " + id + ": missing " + field);
      }
    }
    List<JsonNode> steps = new ArrayList<>();
    for (JsonNode step : node.get("steps")) {
      String kind = kind(step);
      if (!STEPS.contains(kind)) {
        throw new IllegalArgumentException(file + " " + id + ": unknown step " + kind);
      }
      steps.add(step);
    }
    return new AttackCase(
        id,
        node.get("category").asString(),
        node.get("title").asString(),
        node.get("defence").asString(),
        node.get("deterministic").asBoolean(),
        List.copyOf(steps),
        file);
  }

  /**
   * Names a step's kind: its first key.
   *
   * @param step the step
   * @return the kind
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static String kind(JsonNode step) {
    return step.propertyNames().stream().findFirst().orElse("");
  }
}
