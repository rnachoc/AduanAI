// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.inspection;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Replaces sensitive values in JSON trees, and finds which detectors fire on a tree.
 *
 * <p>Two styles (ADR 19): towards the agent a value becomes `[REDACTED:<id>]`; in the log and the
 * approval queue it becomes `[REDACTED:<id>:<hint>]`, where the hint keeps a few characters (the
 * last four of an IBAN, the domain of an e-mail) so an approver can still tell accounts apart
 * without the log ever holding the value.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class Redactor {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * How replaced values look.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public enum Style {
    /** `[REDACTED:iban]`: nothing of the value is left. */
    OPAQUE,
    /** `[REDACTED:iban:XX20…6001]`: enough to recognise, not to reuse. */
    WITH_HINT
  }

  /**
   * A redacted tree and the detectors that fired.
   *
   * @param value the tree with sensitive values replaced
   * @param found the detector ids that matched at least once
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Redacted(JsonNode value, Set<String> found) {}

  /**
   * Prevents instances: the class only has static helpers.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Redactor() {}

  /**
   * Redacts every string in a tree, object keys excepted.
   *
   * @param value the tree
   * @param detectors the detectors to apply
   * @param style how replaced values look
   * @return the redacted copy and what was found
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static Redacted redact(JsonNode value, List<String> detectors, Style style) {
    Set<String> found = new TreeSet<>();
    JsonNode redacted = walk(value, detectors, style, found);
    return new Redacted(redacted, found);
  }

  /**
   * Redacts one string.
   *
   * @param text the text
   * @param detectors the detectors
   * @param style how replaced values look
   * @param found collects detector ids that fired
   * @return the redacted text
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static String redactText(
      String text, List<String> detectors, Style style, Set<String> found) {
    List<Detectors.Match> matches = Detectors.find(text, detectors);
    if (matches.isEmpty()) {
      return text;
    }
    StringBuilder out = new StringBuilder();
    int cursor = 0;
    for (Detectors.Match match : matches) {
      found.add(match.detector());
      out.append(text, cursor, match.start()).append(replacement(match, style));
      cursor = match.end();
    }
    return out.append(text.substring(cursor)).toString();
  }

  /**
   * Lists the detectors that fire anywhere in a tree.
   *
   * @param value the tree
   * @param detectors the detectors to try
   * @return the ids that matched
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static Set<String> detect(JsonNode value, List<String> detectors) {
    return redact(value, detectors, Style.OPAQUE).found();
  }

  /**
   * Rebuilds a tree with redacted strings.
   *
   * @param node the node
   * @param detectors the detectors
   * @param style the style
   * @param found collects detector ids
   * @return the redacted node
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static JsonNode walk(
      JsonNode node, List<String> detectors, Style style, Set<String> found) {
    if (node.isString()) {
      return JSON.getNodeFactory().stringNode(redactText(node.asString(), detectors, style, found));
    }
    if (node.isObject()) {
      ObjectNode copy = JSON.createObjectNode();
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        copy.set(field.getKey(), walk(field.getValue(), detectors, style, found));
      }
      return copy;
    }
    if (node.isArray()) {
      ArrayNode copy = JSON.createArrayNode();
      node.forEach(element -> copy.add(walk(element, detectors, style, found)));
      return copy;
    }
    return node;
  }

  /**
   * Builds the replacement for one match.
   *
   * @param match the match
   * @param style the style
   * @return the replacement text
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static String replacement(Detectors.Match match, Style style) {
    if (style == Style.OPAQUE || "secret".equals(match.detector())) {
      return "[REDACTED:" + match.detector() + "]";
    }
    String value = match.value().replace(" ", "");
    String hint =
        switch (match.detector()) {
          case "email" -> value.charAt(0) + "***" + value.substring(value.lastIndexOf('@'));
          case "iban" -> value.substring(0, 4) + "…" + value.substring(value.length() - 4);
          case "card" ->
              "…" + Detectors.digits(value).substring(Detectors.digits(value).length() - 4);
          default -> "…" + value.substring(value.length() - 3);
        };
    return "[REDACTED:" + match.detector() + ":" + hint + "]";
  }
}
