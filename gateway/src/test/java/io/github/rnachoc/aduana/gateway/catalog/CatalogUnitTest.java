// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rnachoc.aduana.gateway.identity.ApiKeys;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure pieces of identity and catalog: keys, credential sealing, definition pinning, schema refs.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
class CatalogUnitTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void keys_are_unique_prefixed_and_hashed_deterministically() {
    String first = ApiKeys.generate();
    String second = ApiKeys.generate();
    assertThat(first).startsWith("adn_").isNotEqualTo(second).hasSize(4 + 8 + 32);
    assertThat(ApiKeys.visiblePrefix(first)).hasSize(12);
    assertThat(ApiKeys.sha256(first)).isEqualTo(ApiKeys.sha256(first));
  }

  @Test
  void a_sealed_credential_opens_only_for_the_server_it_was_sealed_for() {
    CredentialCipher cipher = new CredentialCipher(CredentialCipher.LOCAL_ONLY);
    UUID tenant = UUID.randomUUID();
    UUID server = UUID.randomUUID();
    CredentialCipher.Sealed sealed = cipher.seal(tenant, server, new Secret("token-123"));
    assertThat(new String(sealed.ciphertext(), java.nio.charset.StandardCharsets.UTF_8))
        .doesNotContain("token-123");
    assertThat(cipher.open(tenant, server, sealed).reveal()).isEqualTo("token-123");
    assertThatThrownBy(() -> cipher.open(tenant, UUID.randomUUID(), sealed))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void a_master_key_that_is_not_32_bytes_is_refused() {
    assertThatThrownBy(() -> new CredentialCipher("c2hvcnQ="))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new CredentialCipher("")).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void a_secret_never_prints_its_value() {
    assertThat(new Secret("hunter2").toString()).doesNotContain("hunter2");
  }

  @Test
  void the_definition_hash_ignores_key_order_and_unpinned_fields() {
    DefinitionHasher hasher = new DefinitionHasher(JSON);
    var a =
        JSON.readTree(
            "{\"name\":\"t\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\",\"required\":[]}}");
    var b =
        JSON.readTree(
            "{\"inputSchema\":{\"required\":[],\"type\":\"object\"},\"description\":\"d\",\"name\":\"t\",\"icons\":[]}");
    var c =
        JSON.readTree(
            "{\"name\":\"t\",\"description\":\"D\",\"inputSchema\":{\"type\":\"object\",\"required\":[]}}");
    assertThat(hasher.sha256(hasher.definition(a))).isEqualTo(hasher.sha256(hasher.definition(b)));
    assertThat(hasher.sha256(hasher.definition(a)))
        .isNotEqualTo(hasher.sha256(hasher.definition(c)));
  }

  @Test
  void a_schema_that_refers_outside_itself_is_detected() {
    assertThat(ArgumentValidator.hasExternalReference(JSON.readTree("{\"$ref\":\"#/$defs/x\"}")))
        .isFalse();
    assertThat(
            ArgumentValidator.hasExternalReference(
                JSON.readTree("{\"properties\":{\"a\":{\"$ref\":\"http://evil/x\"}}}")))
        .isTrue();
    assertThat(
            ArgumentValidator.hasExternalReference(
                JSON.readTree("{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\"}")))
        .isFalse();
  }
}
