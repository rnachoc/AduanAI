// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The JSONPath subset policies may use: `$`, `.field`, `[n]` and `[*]`. Nothing else, so a path can
 * never run code or search the whole document.
 *
 * @param text the path as written
 * @param segments the parsed steps
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record JsonPath(String text, List<Segment> segments) {

  private static final Pattern WHOLE =
      Pattern.compile("\\$(\\.[A-Za-z_][A-Za-z0-9_]*|\\[([0-9]+|\\*)\\])*");
  private static final Pattern STEP =
      Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_]*)|\\[([0-9]+|\\*)\\]");

  /**
   * One step of a path.
   *
   * @param field the object field, or empty for an index step
   * @param index the array index, or -1 for a field or wildcard step
   * @param wildcard whether the step is `[*]`
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Segment(String field, int index, boolean wildcard) {}

  /**
   * The values a path selects and whether a wildcard was crossed.
   *
   * @param values the selected values, in document order
   * @param wildcard whether the path contains `[*]`
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Selection(List<JsonNode> values, boolean wildcard) {}

  /**
   * Copies the segments.
   *
   * @param text the path
   * @param segments its steps
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public JsonPath {
    segments = List.copyOf(segments);
  }

  /**
   * Parses a path.
   *
   * @param text the path, such as `$.items[*].amount`
   * @return the parsed path
   * @throws IllegalArgumentException if the text is outside the subset
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static JsonPath parse(String text) {
    if (!WHOLE.matcher(text).matches()) {
      throw new IllegalArgumentException("Unsupported path " + text);
    }
    List<Segment> segments = new ArrayList<>();
    Matcher step = STEP.matcher(text);
    while (step.find()) {
      if (step.group(1) != null) {
        segments.add(new Segment(step.group(1), -1, false));
      } else if ("*".equals(step.group(2))) {
        segments.add(new Segment("", -1, true));
      } else {
        segments.add(new Segment("", Integer.parseInt(step.group(2)), false));
      }
    }
    return new JsonPath(text, segments);
  }

  /**
   * Selects the values the path points at; missing fields and indexes select nothing.
   *
   * @param root the arguments object
   * @return the selection
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Selection select(JsonNode root) {
    List<JsonNode> current = List.of(root);
    boolean wildcard = false;
    for (Segment segment : segments) {
      List<JsonNode> next = new ArrayList<>();
      for (JsonNode node : current) {
        if (segment.wildcard()) {
          wildcard = true;
          if (node.isArray()) {
            node.forEach(next::add);
          }
        } else if (segment.index() >= 0) {
          if (node.isArray() && segment.index() < node.size()) {
            next.add(node.get(segment.index()));
          }
        } else if (node.isObject() && node.has(segment.field())) {
          next.add(node.get(segment.field()));
        }
      }
      current = next;
    }
    return new Selection(current, wildcard);
  }
}
