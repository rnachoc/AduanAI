// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds bundles and calls for tests from short YAML and JSON snippets.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
final class Policies {

  static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * Prevents instances: the class only has static helpers.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Policies() {}

  /**
   * Parses a policy made of the given rule blocks, failing the test if it is invalid.
   *
   * @param rules YAML list items for the `rules` key, already indented by two spaces
   * @return the bundle
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static PolicyBundle bundle(String rules) {
    PolicyParser.Result result = PolicyParser.parse(policy(rules), Map.of());
    if (result instanceof PolicyParser.Result.Valid(PolicyBundle bundle)) {
      return bundle;
    }
    throw new AssertionError(((PolicyParser.Result.Invalid) result).errors());
  }

  /**
   * Wraps rule blocks in a complete policy document.
   *
   * @param rules the rule blocks
   * @return the document
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static String policy(String rules) {
    return "version: 1\ndefaults:\n  decision: deny\nrules:\n" + rules;
  }

  /**
   * Builds a call from a support agent to a tool with JSON arguments.
   *
   * @param server the server
   * @param tool the tool
   * @param arguments the arguments as JSON
   * @return the call
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static CallContext call(String server, String tool, String arguments) {
    return call(Map.of("team", "support"), server, tool, arguments, CallContext.ContextFacts.CLEAN);
  }

  /**
   * Builds a call with full control.
   *
   * @param attributes the principal's attributes
   * @param server the server
   * @param tool the tool
   * @param arguments the arguments as JSON
   * @param context the marks
   * @return the call
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static CallContext call(
      Map<String, String> attributes,
      String server,
      String tool,
      String arguments,
      CallContext.ContextFacts context) {
    JsonNode args = JSON.readTree(arguments);
    return new CallContext(
        new CallContext.PrincipalFacts("agent-1", attributes, Set.of("agents")),
        new CallContext.ToolFacts(
            server, tool, "refund".equals(tool), "fetch_url".equals(tool), "low"),
        args,
        context);
  }
}
