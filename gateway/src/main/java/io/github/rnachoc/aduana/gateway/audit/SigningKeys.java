// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.audit;

import io.github.rnachoc.aduana.audit.Checkpoints;
import io.github.rnachoc.aduana.gateway.catalog.CredentialCipher;
import io.github.rnachoc.aduana.gateway.catalog.Secret;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Holds each tenant's Ed25519 checkpoint key: generated on first use, its private half sealed with
 * the master key, its public half published at `/.well-known/aduana-audit-key`.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class SigningKeys {

  private static final UUID SEAL_SCOPE =
      UUID.nameUUIDFromBytes("aduana-audit-signing-key".getBytes(StandardCharsets.UTF_8));

  private final JdbcClient jdbc;
  private final CredentialCipher cipher;
  private final Clock clock;
  private final Map<UUID, Key> keys = new ConcurrentHashMap<>();

  /**
   * A loaded key.
   *
   * @param keyId the key id
   * @param publicKey the public key
   * @param privateKey the private key
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Key(String keyId, PublicKey publicKey, PrivateKey privateKey) {}

  /**
   * Creates the holder.
   *
   * @param jdbc the JDBC client
   * @param cipher seals the private key
   * @param clock the clock
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public SigningKeys(JdbcClient jdbc, CredentialCipher cipher, Clock clock) {
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.clock = clock;
  }

  /**
   * Returns the tenant's key, creating it the first time; call inside the append lock.
   *
   * @param tenantId the tenant
   * @return the key
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Key key(UUID tenantId) {
    Key cached = keys.get(tenantId);
    if (cached != null) {
      return cached;
    }
    Key key = load(tenantId).orElseGet(() -> create(tenantId));
    keys.put(tenantId, key);
    return key;
  }

  /**
   * Returns the tenant's public key without creating one.
   *
   * @param tenantId the tenant
   * @return the public key and its id, if a key exists
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<Key> existing(UUID tenantId) {
    return Optional.ofNullable(keys.get(tenantId)).or(() -> load(tenantId));
  }

  /**
   * Reads and unseals a stored key.
   *
   * @param tenantId the tenant
   * @return the key, if stored
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Optional<Key> load(UUID tenantId) {
    return jdbc.sql(
            """
            select key_id, public_key, private_key_ciphertext, private_key_nonce
            from audit_signing_key where tenant_id = :tenant order by created_at desc limit 1
            """)
        .param("tenant", tenantId)
        .query((rs, row) -> {
          Secret sealed =
              cipher.open(
                  tenantId,
                  SEAL_SCOPE,
                  new CredentialCipher.Sealed(
                      rs.getBytes("private_key_ciphertext"), rs.getBytes("private_key_nonce")));
          return new Key(
              rs.getString("key_id"),
              Checkpoints.publicKey(rs.getBytes("public_key")),
              Checkpoints.privateKey(Base64.getDecoder().decode(sealed.reveal())));
        })
        .optional();
  }

  /**
   * Generates and stores a key.
   *
   * @param tenantId the tenant
   * @return the key
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Key create(UUID tenantId) {
    KeyPair pair = Checkpoints.generateKeyPair();
    String keyId = Checkpoints.keyId(pair.getPublic());
    CredentialCipher.Sealed sealed =
        cipher.seal(
            tenantId,
            SEAL_SCOPE,
            new Secret(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())));
    jdbc.sql(
            """
            insert into audit_signing_key (tenant_id, key_id, public_key, private_key_ciphertext,
              private_key_nonce, created_at)
            values (:tenant, :key, :public, :cipher, :nonce, :now)
            """)
        .param("tenant", tenantId)
        .param("key", keyId)
        .param("public", pair.getPublic().getEncoded())
        .param("cipher", sealed.ciphertext())
        .param("nonce", sealed.nonce())
        .param("now", clock.instant().atOffset(ZoneOffset.UTC))
        .update();
    return new Key(keyId, pair.getPublic(), pair.getPrivate());
  }
}
