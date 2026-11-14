// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates and hashes Aduana API keys: `adn_` + 8 visible characters + 32 secret characters.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class ApiKeys {

  /** Every Aduana key starts with this, so secret scanners and people can recognise it. */
  public static final String PREFIX = "adn_";

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  /**
   * Prevents instances: the class only has static helpers.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ApiKeys() {}

  /**
   * Generates a new key with 192 bits of secret randomness after its visible prefix.
   *
   * @return the full key, to show once
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static String generate() {
    byte[] visible = new byte[6];
    byte[] secret = new byte[24];
    RANDOM.nextBytes(visible);
    RANDOM.nextBytes(secret);
    return PREFIX + ENCODER.encodeToString(visible) + ENCODER.encodeToString(secret);
  }

  /**
   * Returns the part of a key that may be displayed.
   *
   * @param key the full key
   * @return `adn_` and the next 8 characters
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static String visiblePrefix(String key) {
    return key.substring(0, Math.min(key.length(), PREFIX.length() + 8));
  }

  /**
   * Hashes a key for storage and lookup.
   *
   * @param key the full key
   * @return its SHA-256
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static byte[] sha256(String key) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", exception);
    }
  }
}
