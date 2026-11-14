// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The test vectors of RFC 8785 (https://www.rfc-editor.org/rfc/rfc8785.txt, read 2026-11-14).
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
class JcsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @ParameterizedTest(name = "{0} serialises as {1}")
  @CsvSource({
    "0000000000000000, 0",
    "8000000000000000, 0",
    "0000000000000001, 5e-324",
    "8000000000000001, -5e-324",
    "7fefffffffffffff, 1.7976931348623157e+308",
    "ffefffffffffffff, -1.7976931348623157e+308",
    "4340000000000000, 9007199254740992",
    "c340000000000000, -9007199254740992",
    "4430000000000000, 295147905179352830000",
    "44b52d02c7e14af5, 9.999999999999997e+22",
    "44b52d02c7e14af6, 1e+23",
    "44b52d02c7e14af7, 1.0000000000000001e+23",
    "444b1ae4d6e2ef4e, 999999999999999700000",
    "444b1ae4d6e2ef4f, 999999999999999900000",
    "444b1ae4d6e2ef50, 1e+21",
    "3eb0c6f7a0b5ed8c, 9.999999999999997e-7",
    "3eb0c6f7a0b5ed8d, 0.000001",
    "41b3de4355555553, 333333333.3333332",
    "41b3de4355555554, 333333333.33333325",
    "41b3de4355555555, 333333333.3333333",
    "41b3de4355555556, 333333333.3333334",
    "41b3de4355555557, 333333333.33333343",
    "becbf647612f3696, -0.0000033333333333333333",
    "43143ff3c1cb0959, 1424953923781206.2"
  })
  void appendix_b_numbers_serialise_as_the_rfc_says(String bits, String expected) {
    assertThat(Jcs.number(Double.longBitsToDouble(Long.parseUnsignedLong(bits, 16))))
        .isEqualTo(expected);
  }

  @Test
  void nan_and_infinity_have_no_canonical_form() {
    assertThatThrownBy(() -> Jcs.number(Double.longBitsToDouble(0x7fffffffffffffffL)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Jcs.number(Double.POSITIVE_INFINITY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void object_keys_sort_by_utf16_code_units() {
    String input =
        "{\"\\u20ac\":\"Euro Sign\",\"\\r\":\"Carriage Return\",\"\\ufb33\":\"Hebrew Letter Dalet With Dagesh\","
            + "\"1\":\"One\",\"\\ud83d\\ude00\":\"Emoji: Grinning Face\",\"\\u0080\":\"Control\","
            + "\"\\u00f6\":\"Latin Small Letter O With Diaeresis\"}";
    String canonical = Jcs.canonicalize(JSON.readTree(input));
    List<String> order = JSON.readTree(canonical).propertyNames().stream().toList();
    assertThat(order)
        .containsExactly("\r", "1", "\u0080", "\u00f6", "\u20ac", "\ud83d\ude00", "\ufb33");
  }

  @Test
  void the_rfc_example_produces_the_rfc_bytes() {
    String input =
        "{\"numbers\": [333333333.33333329, 1E30, 4.50, 2e-3, 0.000000000000000000000000001],"
            + " \"string\": \"\\u20ac$\\u000F\\u000aA'\\u0042\\u0022\\u005c\\\\\\\"\\/\","
            + " \"literals\": [null, true, false]}";
    String expectedHex =
        String.join(
                " ",
                "7b 22 6c 69 74 65 72 61 6c 73 22 3a 5b 6e 75 6c 6c 2c 74 72",
                "75 65 2c 66 61 6c 73 65 5d 2c 22 6e 75 6d 62 65 72 73 22 3a",
                "5b 33 33 33 33 33 33 33 33 33 2e 33 33 33 33 33 33 33 2c 31",
                "65 2b 33 30 2c 34 2e 35 2c 30 2e 30 30 32 2c 31 65 2d 32 37",
                "5d 2c 22 73 74 72 69 6e 67 22 3a 22 e2 82 ac 24 5c 75 30 30",
                "30 66 5c 6e 41 27 42 5c 22 5c 5c 5c 5c 5c 22 2f 22 7d")
            .replace(" ", "");
    byte[] bytes = Jcs.canonicalBytes(JSON.readTree(input));
    assertThat(new String(bytes, StandardCharsets.UTF_8))
        .isEqualTo(
            "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],"
                + "\"string\":\"€$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"}");
    assertThat(HexFormat.of().formatHex(bytes)).isEqualTo(expectedHex);
  }

  @Test
  void control_characters_use_short_escapes_or_lowercase_hex() {
    String canonical =
        Jcs.canonicalize(JSON.getNodeFactory().stringNode("\b\t\n\f\r\u0001\u001f\u007f"));
    assertThat(canonical).isEqualTo("\"\\b\\t\\n\\f\\r\\u0001\\u001f\u007f\"");
  }

  @Test
  void a_lone_surrogate_is_refused() {
    assertThatThrownBy(() -> Jcs.canonicalize(JSON.getNodeFactory().stringNode("a\udeadb")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Jcs.canonicalize(JSON.getNodeFactory().stringNode("\ud83d")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nested_arrays_and_objects_keep_array_order_and_sort_keys() {
    assertThat(Jcs.canonicalize(JSON.readTree("{\"b\":[3,{\"z\":1,\"a\":2}],\"a\":{}}")))
        .isEqualTo("{\"a\":{},\"b\":[3,{\"a\":2,\"z\":1}]}");
  }
}
