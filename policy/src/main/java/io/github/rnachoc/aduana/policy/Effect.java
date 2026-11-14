// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import java.util.Locale;

/**
 * What a rule asks for. Declared from weakest to strongest: when several rules match, the strongest
 * wins, so a deny always beats an approval and an approval always beats an allow.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public enum Effect {
  /** Forward the call. */
  ALLOW,
  /** Forward only after a person approves this exact call. */
  REQUIRE_APPROVAL,
  /** Never forward. */
  DENY;

  /**
   * Returns the stronger of two effects.
   *
   * @param other the other effect
   * @return this or other, whichever is stronger
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Effect strongest(Effect other) {
    return compareTo(other) >= 0 ? this : other;
  }

  /**
   * Returns the spelling used in policy files.
   *
   * @return `allow`, `require_approval` or `deny`
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public String yamlName() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Parses the spelling used in policy files.
   *
   * @param value `allow`, `require_approval` or `deny`
   * @return the effect
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static Effect fromYaml(String value) {
    return valueOf(value.toUpperCase(Locale.ROOT));
  }
}
