// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.audit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The JSON Canonicalization Scheme of RFC 8785: one byte sequence for every JSON value, so a hash
 * of an entry means the same thing to every verifier.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class Jcs {

  /**
   * Prevents instances: the class is a function.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Jcs() {}

  /**
   * Returns the canonical UTF-8 bytes of a value.
   *
   * @param value the value
   * @return the canonical form
   * @throws IllegalArgumentException for NaN, infinities or lone surrogates
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static byte[] canonicalBytes(JsonNode value) {
    return canonicalize(value).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Returns the canonical text of a value.
   *
   * @param value the value
   * @return the canonical form
   * @throws IllegalArgumentException for NaN, infinities or lone surrogates
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static String canonicalize(JsonNode value) {
    StringBuilder out = new StringBuilder();
    write(value, out);
    return out.toString();
  }

  /**
   * Appends one value.
   *
   * @param value the value
   * @param out the output
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static void write(JsonNode value, StringBuilder out) {
    if (value.isObject()) {
      List<String> keys = new ArrayList<>(value.propertyNames());
      // RFC 8785 §3.2.3: keys sort by UTF-16 code units, which is what String.compareTo does.
      keys.sort(String::compareTo);
      out.append('{');
      for (int index = 0; index < keys.size(); index++) {
        out.append(index == 0 ? "" : ",");
        string(keys.get(index), out);
        out.append(':');
        write(value.get(keys.get(index)), out);
      }
      out.append('}');
    } else if (value.isArray()) {
      out.append('[');
      for (int index = 0; index < value.size(); index++) {
        out.append(index == 0 ? "" : ",");
        write(value.get(index), out);
      }
      out.append(']');
    } else if (value.isString()) {
      string(value.asString(), out);
    } else if (value.isNumber()) {
      out.append(number(value.doubleValue()));
    } else if (value.isBoolean()) {
      out.append(value.asBoolean());
    } else if (value.isNull()) {
      out.append("null");
    } else {
      throw new IllegalArgumentException("Not a JSON value: " + value.getNodeType());
    }
  }

  /**
   * Appends a string with the escaping of RFC 8785 §3.2.2.2.
   *
   * @param text the string
   * @param out the output
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static void string(String text, StringBuilder out) {
    out.append('"');
    for (int index = 0; index < text.length(); index++) {
      char c = text.charAt(index);
      if (Character.isSurrogate(c)) {
        boolean paired =
            Character.isHighSurrogate(c)
                && index + 1 < text.length()
                && Character.isLowSurrogate(text.charAt(index + 1));
        if (!paired) {
          throw new IllegalArgumentException("Lone surrogate at " + index);
        }
        out.append(c).append(text.charAt(++index));
        continue;
      }
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\t' -> out.append("\\t");
        case '\n' -> out.append("\\n");
        case '\f' -> out.append("\\f");
        case '\r' -> out.append("\\r");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  /**
   * Formats a number as ECMAScript's Number.prototype.toString does (ECMA-262 §7.1.12.1).
   *
   * <p>Java 19+ `Double.toString` yields the shortest digits that round-trip, the same digits
   * ECMAScript chooses; only the layout differs, and that is rebuilt here.
   *
   * @param value the number
   * @return its canonical text
   * @throws IllegalArgumentException for NaN and infinities
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static String number(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      throw new IllegalArgumentException("NaN and infinities have no JSON form");
    }
    if (value == 0) {
      return "0";
    }
    BigDecimal exact = new BigDecimal(Double.toString(Math.abs(value))).stripTrailingZeros();
    // Java prints at least two significant digits (4.9E-324); ECMAScript prefers one when one
    // digit already round-trips (5e-324).
    BigDecimal oneDigit = exact.round(new MathContext(1, RoundingMode.HALF_EVEN));
    if (exact.precision() == 2 && oneDigit.doubleValue() == Math.abs(value)) {
      exact = oneDigit.stripTrailingZeros();
    }
    String digits = exact.unscaledValue().toString();
    int k = digits.length();
    int n = k - exact.scale();
    String sign = value < 0 ? "-" : "";
    if (k <= n && n <= 21) {
      return sign + digits + "0".repeat(n - k);
    }
    if (0 < n && n <= 21) {
      return sign + digits.substring(0, n) + "." + digits.substring(n);
    }
    if (-6 < n && n <= 0) {
      return sign + "0." + "0".repeat(-n) + digits;
    }
    String mantissa = k == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
    int exponent = n - 1;
    return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + Math.abs(exponent);
  }
}
