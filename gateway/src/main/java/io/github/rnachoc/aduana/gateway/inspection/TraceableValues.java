// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.inspection;

import io.github.rnachoc.aduana.audit.HashChain;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Extracts the "traceable values" of plan §5.6 (URLs, e-mail addresses, IBANs, numbers of more than
 * eight digits, and strings of 24 characters or more without spaces) and hashes their normalised
 * form.
 *
 * <p>Only literal copies are caught: a value that is reformatted, translated or paraphrased between
 * a result and an argument no longer matches. It is a signal for policy, not a guarantee.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class TraceableValues {

  private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>]+");
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}\\b");
  private static final Pattern LONG_NUMBER = Pattern.compile("\\d{9,}");
  private static final Pattern LONG_TOKEN = Pattern.compile("[^\\s\"'<>]{24,}");
  private static final String TRAILING = ".,;:!?)]}>'\"";

  /**
   * Prevents instances: the class only has static helpers.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private TraceableValues() {}

  /**
   * Hashes every traceable value in every string of a tree, with the JSONPath where it was found.
   *
   * @param root the tree
   * @return value hash to the first path it was seen at
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static Map<String, String> hashes(JsonNode root) {
    Map<String, String> found = new LinkedHashMap<>();
    walk(root, "$", found);
    return found;
  }

  /**
   * Hashes the traceable values of one text.
   *
   * @param text the text
   * @param path where it was found
   * @param found collects hash to path
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static void extract(String text, String path, Map<String, String> found) {
    add(URL.matcher(text), true, path, found);
    add(EMAIL.matcher(text), true, path, found);
    Matcher iban = IBAN.matcher(text);
    while (iban.find()) {
      found.putIfAbsent(hash(iban.group().replace(" ", "")), path);
    }
    add(LONG_NUMBER.matcher(text), false, path, found);
    add(LONG_TOKEN.matcher(text), false, path, found);
  }

  /**
   * Adds the normalised hashes of every match.
   *
   * @param matcher the matcher
   * @param lowercase whether case is irrelevant for this kind of value
   * @param path where the text was found
   * @param found collects hash to path
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static void add(
      Matcher matcher, boolean lowercase, String path, Map<String, String> found) {
    while (matcher.find()) {
      String value = trim(matcher.group());
      if (!value.isEmpty()) {
        found.putIfAbsent(hash(lowercase ? value.toLowerCase(Locale.ROOT) : value), path);
      }
    }
  }

  /**
   * Walks a tree, building JSONPaths in the policy subset.
   *
   * @param node the node
   * @param path its path
   * @param found collects hash to path
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static void walk(JsonNode node, String path, Map<String, String> found) {
    if (node.isString()) {
      extract(node.asString(), path, found);
    } else if (node.isObject()) {
      node.properties()
          .forEach(field -> walk(field.getValue(), path + "." + field.getKey(), found));
    } else if (node.isArray()) {
      for (int index = 0; index < node.size(); index++) {
        walk(node.get(index), path + "[" + index + "]", found);
      }
    }
  }

  /**
   * Drops trailing punctuation that sentences put after a value.
   *
   * @param value the matched text
   * @return the value without trailing punctuation
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String trim(String value) {
    int end = value.length();
    while (end > 0 && TRAILING.indexOf(value.charAt(end - 1)) >= 0) {
      end--;
    }
    return value.substring(0, end);
  }

  /**
   * Hashes a normalised value.
   *
   * @param value the value
   * @return hex SHA-256
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String hash(String value) {
    return HashChain.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
  }
}
