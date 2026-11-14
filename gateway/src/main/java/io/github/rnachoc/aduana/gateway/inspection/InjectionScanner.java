// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.inspection;

import io.github.rnachoc.aduana.policy.BoundedRegex;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Flags text that addresses an AI agent instead of a person, using the versioned patterns in
 * `injection-patterns.yaml`. A heuristic: it marks, it never decides (ADR 2), and paraphrases
 * escape it.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class InjectionScanner {

  private final Map<String, BoundedRegex> patterns = new LinkedHashMap<>();
  private final int version;

  /**
   * Loads the shipped patterns.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public InjectionScanner() {
    try (InputStream input =
        InjectionScanner.class.getResourceAsStream("/injection-patterns.yaml")) {
      if (input == null) {
        throw new IllegalStateException("injection-patterns.yaml is missing");
      }
      JsonNode document = YAMLMapper.builder().build().readTree(input);
      version = document.path("version").asInt();
      for (JsonNode pattern : document.path("patterns")) {
        patterns.put(
            pattern.path("id").asString(), new BoundedRegex(pattern.path("regex").asString()));
      }
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Returns the ids of the patterns found in a text.
   *
   * @param text the text
   * @return pattern ids, in file order
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<String> scan(String text) {
    List<String> found = new ArrayList<>();
    patterns.forEach((id, pattern) -> {
      if (pattern.find(text)) {
        found.add(id);
      }
    });
    return found;
  }

  /**
   * Returns the ids of the patterns found in any string of a JSON tree.
   *
   * @param value the tree
   * @return pattern ids, without repeats
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<String> scan(JsonNode value) {
    List<String> found = new ArrayList<>();
    collect(value, found);
    return found;
  }

  /**
   * Returns the version of the pattern list, recorded with every flag.
   *
   * @return the version
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public int version() {
    return version;
  }

  /**
   * Scans every string of a tree.
   *
   * @param node the node
   * @param found collects pattern ids
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private void collect(JsonNode node, List<String> found) {
    if (node.isString()) {
      scan(node.asString()).stream().filter(id -> !found.contains(id)).forEach(found::add);
    } else {
      node.forEach(child -> collect(child, found));
    }
  }
}
