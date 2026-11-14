// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reads a policy bundle directory: `policy.yaml` and every `tests/*.yaml`.
 *
 * @param policy the text of `policy.yaml`
 * @param tests test file name to its text, sorted by name
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record BundleFiles(String policy, Map<String, String> tests) {

  /**
   * Reads a bundle directory.
   *
   * @param directory the bundle directory
   * @return its files
   * @throws IOException if `policy.yaml` is missing or a file cannot be read
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static BundleFiles read(Path directory) throws IOException {
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
    return new BundleFiles(policy, tests);
  }
}
