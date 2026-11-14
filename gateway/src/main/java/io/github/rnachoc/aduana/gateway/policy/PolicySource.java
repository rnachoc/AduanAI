// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.policy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The texts of a policy bundle: `policy.yaml` and its test files.
 *
 * @param policy the policy text
 * @param tests test file name to text
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record PolicySource(String policy, Map<String, String> tests) {

  /**
   * Copies the tests in name order.
   *
   * @param policy the policy text
   * @param tests the test texts
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public PolicySource {
    Objects.requireNonNull(policy, "policy");
    tests = Map.copyOf(new TreeMap<>(tests));
  }

  /**
   * Reads a bundle directory: `policy.yaml` and `tests/*.yaml`.
   *
   * @param directory the directory
   * @return the texts
   * @throws IOException if the policy file is missing or unreadable
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static PolicySource read(Path directory) throws IOException {
    String policy = Files.readString(directory.resolve("policy.yaml"), StandardCharsets.UTF_8);
    Map<String, String> tests = new TreeMap<>();
    Path testDirectory = directory.resolve("tests");
    if (Files.isDirectory(testDirectory)) {
      try (Stream<Path> files = Files.list(testDirectory)) {
        for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".yaml")).toList()) {
          tests.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
        }
      }
    }
    return new PolicySource(policy, tests);
  }
}
