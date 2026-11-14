// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gives a policy an identity independent of formatting: the SHA-256 of its parsed content written
 * as compact JSON with every object's keys sorted.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
final class Canonical {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * Prevents instances: the class only has static helpers.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Canonical() {}

  /**
   * Hashes a JSON value independently of key order and whitespace.
   *
   * @param node the value
   * @return lowercase hex SHA-256
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static String sha256(JsonNode node) {
    byte[] bytes = JSON.writeValueAsString(sorted(node)).getBytes(StandardCharsets.UTF_8);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", exception);
    }
  }

  /**
   * Rebuilds a value with sorted object keys.
   *
   * @param node the value
   * @return an equal value with sorted keys
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static JsonNode sorted(JsonNode node) {
    if (node.isObject()) {
      Map<String, JsonNode> fields = new TreeMap<>();
      node.properties().forEach(entry -> fields.put(entry.getKey(), sorted(entry.getValue())));
      ObjectNode copy = JSON.createObjectNode();
      fields.forEach(copy::set);
      return copy;
    }
    if (node.isArray()) {
      ArrayNode copy = JSON.createArrayNode();
      node.forEach(element -> copy.add(sorted(element)));
      return copy;
    }
    return node;
  }
}
