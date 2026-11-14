// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.audit;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Links log entries: each entry's hash covers the previous hash and the entry's canonical form, so
 * changing, removing or reordering any entry breaks every hash after it.
 *
 * <p>`hash = SHA-256(prev_hash_bytes || JCS(entry without "hash"))`, lowercase hex (plan §5.5).
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class HashChain {

  /** The `prev_hash` of the first entry: 64 zeros. */
  public static final String GENESIS = "0".repeat(64);

  /**
   * Prevents instances: the class is a function.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private HashChain() {}

  /**
   * Computes an entry's hash.
   *
   * @param entry the entry; a `hash` field, if present, is ignored
   * @return lowercase hex SHA-256
   * @throws IllegalArgumentException if `prev_hash` is not 64 hex characters
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static String hash(JsonNode entry) {
    String previous = entry.path("prev_hash").asString("");
    if (!previous.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("prev_hash must be 64 lowercase hex characters");
    }
    ObjectNode body = ((ObjectNode) entry).deepCopy();
    body.remove("hash");
    MessageDigest digest = sha256();
    digest.update(HexFormat.of().parseHex(previous));
    digest.update(Jcs.canonicalBytes(body));
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * Hashes bytes.
   *
   * @param bytes the bytes
   * @return lowercase hex SHA-256
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static String sha256Hex(byte[] bytes) {
    return HexFormat.of().formatHex(sha256().digest(bytes));
  }

  /**
   * Returns a fresh SHA-256 digest.
   *
   * @return the digest
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", exception);
    }
  }
}
