// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.identity;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes principals and their API keys with explicit SQL.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Repository
public class PrincipalRepository {

  private static final TypeReference<Map<String, String>> ATTRIBUTES = new TypeReference<>() {};

  private final JdbcClient jdbc;
  private final JsonMapper json;

  /**
   * Creates the repository.
   *
   * @param jdbc the JDBC client
   * @param json the shared JSON mapper
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public PrincipalRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Inserts a principal.
   *
   * @param principal the principal
   * @param now the creation time
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void insert(Principal principal, Instant now) {
    jdbc.sql(
            """
            insert into principal (tenant_id, id, kind, name, attributes, groups, status, created_at)
            values (:tenant, :id, :kind, :name, cast(:attributes as jsonb), :groups, :status, :now)
            """)
        .param("tenant", principal.tenantId())
        .param("id", principal.id())
        .param("kind", principal.kind().name().toLowerCase(Locale.ROOT))
        .param("name", principal.name())
        .param("attributes", json.writeValueAsString(principal.attributes()))
        .param("groups", principal.groups().toArray(String[]::new))
        .param("status", principal.status())
        .param("now", utc(now))
        .update();
  }

  /**
   * Finds a principal by id.
   *
   * @param tenantId the tenant
   * @param id the principal id
   * @return the principal, if it exists
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<Principal> find(UUID tenantId, UUID id) {
    return jdbc.sql("select * from principal where tenant_id = :tenant and id = :id")
        .param("tenant", tenantId)
        .param("id", id)
        .query(this::map)
        .optional();
  }

  /**
   * Finds a principal by name.
   *
   * @param tenantId the tenant
   * @param name the principal name
   * @return the principal, if it exists
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<Principal> findByName(UUID tenantId, String name) {
    return jdbc.sql("select * from principal where tenant_id = :tenant and name = :name")
        .param("tenant", tenantId)
        .param("name", name)
        .query(this::map)
        .optional();
  }

  /**
   * Lists every principal of a tenant by name.
   *
   * @param tenantId the tenant
   * @return the principals
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<Principal> list(UUID tenantId) {
    return jdbc.sql("select * from principal where tenant_id = :tenant order by name")
        .param("tenant", tenantId)
        .query(this::map)
        .list();
  }

  /**
   * Inserts an API key; only its SHA-256 is stored.
   *
   * @param key the key metadata
   * @param sha256 the hash of the full key
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void insertKey(ApiKey key, byte[] sha256) {
    jdbc.sql(
            """
            insert into api_key (tenant_id, id, principal_id, prefix, key_sha256, created_at, expires_at)
            values (:tenant, :id, :principal, :prefix, :hash, :created, :expires)
            """)
        .param("tenant", key.tenantId())
        .param("id", key.id())
        .param("principal", key.principalId())
        .param("prefix", key.prefix())
        .param("hash", sha256)
        .param("created", utc(key.createdAt()))
        .param("expires", key.expiresAt().map(PrincipalRepository::utc).orElse(null))
        .update();
  }

  /**
   * Finds the active principal that owns a key hash, if the key is neither revoked nor expired.
   *
   * @param sha256 the hash of the presented key
   * @param now the current time
   * @return the principal
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<Principal> findByKeyHash(byte[] sha256, Instant now) {
    return jdbc.sql(
            """
            select p.* from api_key k
            join principal p on p.tenant_id = k.tenant_id and p.id = k.principal_id
            where k.key_sha256 = :hash and k.revoked_at is null
              and (k.expires_at is null or k.expires_at > :now) and p.status = 'active'
            """)
        .param("hash", sha256)
        .param("now", utc(now))
        .query(this::map)
        .optional();
  }

  /**
   * Revokes a key.
   *
   * @param tenantId the tenant
   * @param keyId the key id
   * @param now the revocation time
   * @return true if a key that was not revoked yet was revoked
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public boolean revokeKey(UUID tenantId, UUID keyId, Instant now) {
    return jdbc.sql(
                """
                update api_key set revoked_at = :now
                where tenant_id = :tenant and id = :id and revoked_at is null
                """)
            .param("now", utc(now))
            .param("tenant", tenantId)
            .param("id", keyId)
            .update()
        == 1;
  }

  /**
   * Lists the keys of a principal, without their hashes.
   *
   * @param tenantId the tenant
   * @param principalId the principal
   * @return the keys, newest first
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<ApiKey> listKeys(UUID tenantId, UUID principalId) {
    return jdbc.sql(
            """
            select * from api_key where tenant_id = :tenant and principal_id = :principal
            order by created_at desc
            """)
        .param("tenant", tenantId)
        .param("principal", principalId)
        .query((rs, row) ->
            new ApiKey(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("id", UUID.class),
                rs.getObject("principal_id", UUID.class),
                rs.getString("prefix"),
                instant(rs, "created_at").orElseThrow(),
                instant(rs, "expires_at"),
                instant(rs, "revoked_at")))
        .list();
  }

  /**
   * Maps a principal row.
   *
   * @param rs the result set
   * @param row the row number
   * @return the principal
   * @throws SQLException if a column cannot be read
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Principal map(ResultSet rs, int row) throws SQLException {
    Array groups = rs.getArray("groups");
    return new Principal(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("id", UUID.class),
        Principal.Kind.valueOf(rs.getString("kind").toUpperCase(Locale.ROOT)),
        rs.getString("name"),
        json.readValue(rs.getString("attributes"), ATTRIBUTES),
        Arrays.asList((String[]) groups.getArray()),
        rs.getString("status"));
  }

  /**
   * Reads a nullable timestamp column.
   *
   * @param rs the result set
   * @param column the column name
   * @return the instant, if not null
   * @throws SQLException if the column cannot be read
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static Optional<Instant> instant(ResultSet rs, String column) throws SQLException {
    return Optional.ofNullable(rs.getObject(column, OffsetDateTime.class))
        .map(OffsetDateTime::toInstant);
  }

  /**
   * Converts an instant to the type the PostgreSQL driver maps to timestamptz.
   *
   * @param instant the instant
   * @return the same instant at UTC
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
