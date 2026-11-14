// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.catalog;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Checks tool arguments against the input schema pinned in the catalog, not the one the server
 * publishes today.
 *
 * <p>Schemas come from third parties, so a schema that refers to anything outside itself (`$ref` to
 * a URL) is refused rather than fetched: validating must never make the gateway call out.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class ArgumentValidator {

  private static final int MAX_CACHED = 1000;

  private final SchemaRegistry registry =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
  private final Map<String, Schema> compiled = new ConcurrentHashMap<>();

  /**
   * Validates arguments, and with `strict` also refuses top-level fields the schema does not name.
   *
   * @param tool the pinned tool
   * @param arguments the call's arguments object
   * @param strict whether unknown top-level fields are refused
   * @return the problems found; empty when the arguments are valid
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<String> validate(CatalogTool tool, JsonNode arguments, boolean strict) {
    JsonNode schema = tool.definition().path("inputSchema");
    if (!schema.isObject()) {
      return List.of("the tool has no input schema");
    }
    if (hasExternalReference(schema)) {
      return List.of("the input schema refers outside itself");
    }
    List<String> problems = new ArrayList<>();
    if (strict) {
      JsonNode properties = schema.path("properties");
      arguments
          .propertyNames()
          .forEach(name -> {
            if (!properties.has(name)) {
              problems.add("unknown field " + name);
            }
          });
    }
    Schema validator = compile(tool.definitionSha256(), schema);
    for (Error error : validator.validate(arguments)) {
      problems.add(error.getMessage());
    }
    return problems;
  }

  /**
   * Compiles a schema once per pinned definition.
   *
   * @param key the definition hash
   * @param schema the input schema
   * @return the compiled schema
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Schema compile(String key, JsonNode schema) {
    if (compiled.size() > MAX_CACHED) {
      compiled.clear();
    }
    return compiled.computeIfAbsent(key, ignored -> registry.getSchema(schema));
  }

  /**
   * Looks for a `$ref` or `$dynamicRef` that does not start with `#`.
   *
   * @param node a schema or part of one
   * @return true when the schema points outside itself
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static boolean hasExternalReference(JsonNode node) {
    if (node.isObject()) {
      for (String keyword : List.of("$ref", "$dynamicRef", "$schema")) {
        JsonNode value = node.path(keyword);
        boolean allowedDialect =
            "$schema".equals(keyword) && value.asString("").startsWith("https://json-schema.org/");
        if (value.isString() && !value.asString().startsWith("#") && !allowedDialect) {
          return true;
        }
      }
      for (JsonNode child : node) {
        if (hasExternalReference(child)) {
          return true;
        }
      }
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        if (hasExternalReference(child)) {
          return true;
        }
      }
    }
    return false;
  }
}
