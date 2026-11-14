// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.web;

import io.github.rnachoc.aduana.audit.Checkpoints;
import io.github.rnachoc.aduana.gateway.audit.SigningKeys;
import io.github.rnachoc.aduana.gateway.identity.Tenants;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the public key that checks checkpoint signatures, so anyone can verify an export.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@RestController
public class AuditKeyController {

  private final SigningKeys keys;

  /**
   * Creates the controller.
   *
   * @param keys the signing keys
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public AuditKeyController(SigningKeys keys) {
    this.keys = keys;
  }

  /**
   * Returns the key: algorithm, id, the raw 32 bytes and the X.509 SubjectPublicKeyInfo, base64.
   *
   * @return the key, or 404 before the first checkpoint
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @GetMapping("/.well-known/aduana-audit-key")
  public ResponseEntity<Map<String, String>> key() {
    return keys.existing(Tenants.DEFAULT)
        .map(key ->
            ResponseEntity.ok(
                Map.of(
                    "algorithm", "Ed25519",
                    "key_id", key.keyId(),
                    "tenant_id", Tenants.DEFAULT.toString(),
                    "public_key",
                        Base64.getEncoder()
                            .encodeToString(Checkpoints.rawPublicKey(key.publicKey())),
                    "spki", Base64.getEncoder().encodeToString(key.publicKey().getEncoded()))))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
