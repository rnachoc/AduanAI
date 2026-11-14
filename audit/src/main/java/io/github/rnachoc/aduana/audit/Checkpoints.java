// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.audit;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Signs and checks checkpoints: an Ed25519 signature over the canonical form of `{tenant_id, seq,
 * hash, ts}`. A checkpoint kept outside the database proves that the log had at least `seq` entries
 * ending in `hash`, so even deleting the whole tail is detected.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class Checkpoints {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ALGORITHM = "Ed25519";
  private static final int SPKI_PREFIX = 12;

  /**
   * Prevents instances: the class only has static helpers.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Checkpoints() {}

  /**
   * Generates a signing key pair.
   *
   * @return a new Ed25519 key pair
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static KeyPair generateKeyPair() {
    try {
      return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Ed25519 is required by every Java 15+ platform", exception);
    }
  }

  /**
   * Builds and signs a checkpoint over the entry at `seq`.
   *
   * @param key the private key
   * @param keyId the id of the key pair
   * @param tenantId the tenant
   * @param seq the entry's sequence number
   * @param hash the entry's hash
   * @param ts when the checkpoint is made, RFC 3339 UTC
   * @return the checkpoint, with `signature` in base64 and `key_id`
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static ObjectNode sign(
      PrivateKey key, String keyId, String tenantId, long seq, String hash, String ts) {
    ObjectNode checkpoint = signedPart(tenantId, seq, hash, ts);
    try {
      Signature signature = Signature.getInstance(ALGORITHM);
      signature.initSign(key);
      signature.update(Jcs.canonicalBytes(checkpoint));
      checkpoint.put("signature", Base64.getEncoder().encodeToString(signature.sign()));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Cannot sign a checkpoint", exception);
    }
    checkpoint.put("key_id", keyId);
    return checkpoint;
  }

  /**
   * Checks a checkpoint's signature.
   *
   * @param key the public key
   * @param checkpoint the checkpoint as exported
   * @return true when the signature covers exactly this tenant, seq, hash and time
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static boolean verify(PublicKey key, JsonNode checkpoint) {
    ObjectNode signed =
        signedPart(
            checkpoint.path("tenant_id").asString(),
            checkpoint.path("seq").asLong(),
            checkpoint.path("hash").asString(),
            checkpoint.path("ts").asString());
    try {
      Signature signature = Signature.getInstance(ALGORITHM);
      signature.initVerify(key);
      signature.update(Jcs.canonicalBytes(signed));
      return signature.verify(Base64.getDecoder().decode(checkpoint.path("signature").asString()));
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      return false;
    }
  }

  /**
   * Returns the raw 32-byte public key, the form other verifiers load most easily.
   *
   * @param key the public key
   * @return the raw key bytes
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static byte[] rawPublicKey(PublicKey key) {
    byte[] spki = key.getEncoded();
    return Arrays.copyOfRange(spki, SPKI_PREFIX, spki.length);
  }

  /**
   * Rebuilds a public key from its X.509 (SubjectPublicKeyInfo) encoding.
   *
   * @param encoded the DER bytes
   * @return the key
   * @throws IllegalArgumentException if the bytes are not an Ed25519 public key
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static PublicKey publicKey(byte[] encoded) {
    try {
      return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(encoded));
    } catch (GeneralSecurityException exception) {
      throw new IllegalArgumentException("Not an Ed25519 public key", exception);
    }
  }

  /**
   * Rebuilds a private key from its PKCS#8 encoding.
   *
   * @param encoded the DER bytes
   * @return the key
   * @throws IllegalArgumentException if the bytes are not an Ed25519 private key
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static PrivateKey privateKey(byte[] encoded) {
    try {
      return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(encoded));
    } catch (GeneralSecurityException exception) {
      throw new IllegalArgumentException("Not an Ed25519 private key", exception);
    }
  }

  /**
   * Names a key by the first 16 hex characters of the SHA-256 of its raw bytes.
   *
   * @param key the public key
   * @return the key id
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static String keyId(PublicKey key) {
    return HashChain.sha256Hex(rawPublicKey(key)).substring(0, 16);
  }

  /**
   * Builds the object the signature covers.
   *
   * @param tenantId the tenant
   * @param seq the sequence number
   * @param hash the entry hash
   * @param ts the checkpoint time
   * @return the signed object
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static ObjectNode signedPart(String tenantId, long seq, String hash, String ts) {
    ObjectNode node = JSON.createObjectNode();
    node.put("tenant_id", tenantId);
    node.put("seq", seq);
    node.put("hash", hash);
    node.put("ts", ts);
    return node;
  }
}
