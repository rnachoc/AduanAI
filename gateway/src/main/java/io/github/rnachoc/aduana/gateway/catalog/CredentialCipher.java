// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.catalog;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts upstream credentials with AES-256-GCM under the master key from the environment.
 *
 * <p>The ciphertext is bound to the tenant and server ids as associated data, so a ciphertext
 * copied onto another server's row does not decrypt.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class CredentialCipher {

  /** The master key value that selects a fixed, insecure key for local development only. */
  public static final String LOCAL_ONLY = "local-only";

  private static final Logger LOG = LoggerFactory.getLogger(CredentialCipher.class);
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKey key;
  private final SecureRandom random = new SecureRandom();

  /**
   * A ciphertext and the nonce it was sealed with.
   *
   * @param ciphertext the encrypted credential and its tag
   * @param nonce the 96-bit GCM nonce
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Sealed(byte[] ciphertext, byte[] nonce) {}

  /**
   * Loads the master key.
   *
   * @param masterKey base64 of 32 random bytes, or `local-only` for development
   * @throws IllegalStateException if the key is missing or not 32 bytes
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public CredentialCipher(@Value("${aduana.master-key:}") String masterKey) {
    this.key = new SecretKeySpec(decodeKey(masterKey), "AES");
  }

  /**
   * Turns the configured value into 32 key bytes.
   *
   * @param masterKey the configured value
   * @return the key bytes
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static byte[] decodeKey(String masterKey) {
    if (LOCAL_ONLY.equals(masterKey)) {
      LOG.warn("ADUANA_MASTER_KEY is local-only: upstream credentials are not protected at rest");
      return sha256(LOCAL_ONLY);
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(masterKey);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("ADUANA_MASTER_KEY must be base64", exception);
    }
    if (bytes.length != 32) {
      throw new IllegalStateException("ADUANA_MASTER_KEY must decode to 32 bytes");
    }
    return bytes;
  }

  /**
   * Encrypts a credential for one server.
   *
   * @param tenantId the tenant
   * @param serverId the server the credential belongs to
   * @param credential the credential
   * @return the ciphertext and nonce
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Sealed seal(UUID tenantId, UUID serverId, Secret credential) {
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(associatedData(tenantId, serverId));
      byte[] ciphertext = cipher.doFinal(credential.reveal().getBytes(StandardCharsets.UTF_8));
      return new Sealed(ciphertext, nonce);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("AES-GCM is required by every Java platform", exception);
    }
  }

  /**
   * Decrypts a server's credential.
   *
   * @param tenantId the tenant
   * @param serverId the server
   * @param sealed the stored ciphertext and nonce
   * @return the credential
   * @throws IllegalStateException if the ciphertext was altered or sealed for another server
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Secret open(UUID tenantId, UUID serverId, Sealed sealed) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed.nonce()));
      cipher.updateAAD(associatedData(tenantId, serverId));
      return new Secret(new String(cipher.doFinal(sealed.ciphertext()), StandardCharsets.UTF_8));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(
          "Stored credential does not decrypt for this server", exception);
    }
  }

  /**
   * Builds the associated data that binds a ciphertext to its row.
   *
   * @param tenantId the tenant
   * @param serverId the server
   * @return the bytes
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static byte[] associatedData(UUID tenantId, UUID serverId) {
    return (tenantId + "/" + serverId).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Hashes a string.
   *
   * @param text the text
   * @return its SHA-256
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static byte[] sha256(String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("SHA-256 is required by every Java platform", exception);
    }
  }
}
