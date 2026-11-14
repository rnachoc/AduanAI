// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.catalog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pins tool definitions: the part of a tool an agent reads, in a key-sorted form, and its hash.
 *
 * <p>Only the fields that can change what an agent does are pinned: name, title, description, input
 * and output schemas and annotations. Icons and `_meta` are dropped before hashing.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class DefinitionHasher {

  private static final String[] PINNED = {
    "name", "title", "description", "inputSchema", "outputSchema", "annotations"
  };

  private final JsonMapper json;

  /**
   * Creates the hasher.
   *
   * @param json the shared JSON mapper
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public DefinitionHasher(JsonMapper json) {
    this.json = json;
  }

  /**
   * Keeps the pinned fields of a tool as the server published it.
   *
   * @param published the tool object from `tools/list`
   * @return the definition to store
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ObjectNode definition(JsonNode published) {
    ObjectNode definition = json.createObjectNode();
    for (String field : PINNED) {
      if (published.has(field)) {
        definition.set(field, published.get(field));
      }
    }
    return definition;
  }

  /**
   * Hashes a definition independently of key order.
   *
   * @param definition the definition
   * @return lowercase hex SHA-256
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public String sha256(JsonNode definition) {
    byte[] canonical = json.writeValueAsString(sorted(definition)).getBytes(StandardCharsets.UTF_8);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", exception);
    }
  }

  /**
   * Rebuilds a JSON value with every object's keys in sorted order.
   *
   * @param node the value
   * @return an equal value with sorted keys
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private JsonNode sorted(JsonNode node) {
    if (node.isObject()) {
      Map<String, JsonNode> fields = new TreeMap<>();
      node.properties().forEach(entry -> fields.put(entry.getKey(), sorted(entry.getValue())));
      ObjectNode copy = json.createObjectNode();
      fields.forEach(copy::set);
      return copy;
    }
    if (node.isArray()) {
      ArrayNode copy = json.createArrayNode();
      node.forEach(element -> copy.add(sorted(element)));
      return copy;
    }
    return node;
  }
}
