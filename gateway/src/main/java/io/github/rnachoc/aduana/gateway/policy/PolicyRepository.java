// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.policy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stores policy bundles as their source text, with one active bundle per tenant.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Repository
public class PolicyRepository {

  private final JdbcClient jdbc;
  private final JsonMapper json;

  /**
   * The active bundle's stored form.
   *
   * @param sha256 its identity
   * @param source the policy and test texts
   * @param loadedAt when it was activated
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Stored(String sha256, PolicySource source, Instant loadedAt) {}

  /**
   * Creates the repository.
   *
   * @param jdbc the JDBC client
   * @param json the shared JSON mapper
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public PolicyRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Makes a bundle the active one; the caller runs this inside a transaction.
   *
   * @param tenantId the tenant
   * @param sha256 the bundle identity
   * @param source the texts
   * @param actorId who loaded it, if a person did
   * @param now the time
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void activate(
      UUID tenantId, String sha256, PolicySource source, Optional<UUID> actorId, Instant now) {
    jdbc.sql("update policy_bundle set active = false where tenant_id = :tenant and active")
        .param("tenant", tenantId)
        .update();
    ObjectNode content = json.createObjectNode();
    content.put("policy", source.policy());
    ObjectNode tests = content.putObject("tests");
    source.tests().forEach(tests::put);
    jdbc.sql(
            """
            insert into policy_bundle (tenant_id, id, sha256, content, loaded_at, loaded_by, active)
            values (:tenant, :id, :sha, cast(:content as jsonb), :now, :actor, true)
            """)
        .param("tenant", tenantId)
        .param("id", UUID.randomUUID())
        .param("sha", sha256)
        .param("content", json.writeValueAsString(content))
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("actor", actorId.orElse(null))
        .update();
  }

  /**
   * Reads the active bundle of a tenant.
   *
   * @param tenantId the tenant
   * @return the bundle's texts and identity, if one is active
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<Stored> active(UUID tenantId) {
    return jdbc.sql(
            "select sha256, content, loaded_at from policy_bundle where tenant_id = :tenant and active")
        .param("tenant", tenantId)
        .query((rs, row) -> {
          var content = json.readTree(rs.getString("content"));
          java.util.Map<String, String> tests = new java.util.TreeMap<>();
          content
              .path("tests")
              .properties()
              .forEach(e -> tests.put(e.getKey(), e.getValue().asString()));
          return new Stored(
              rs.getString("sha256"),
              new PolicySource(content.path("policy").asString(), tests),
              rs.getObject("loaded_at", OffsetDateTime.class).toInstant());
        })
        .optional();
  }
}
