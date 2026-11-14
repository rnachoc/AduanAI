// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes the catalog of upstream servers and pinned tools with explicit SQL.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Repository
public class CatalogRepository {

  private static final String TOOL_COLUMNS =
      "t.*, s.name as server_name from tool t join upstream_server s"
          + " on s.tenant_id = t.tenant_id and s.id = t.server_id";

  private final JdbcClient jdbc;
  private final JsonMapper json;

  /**
   * Creates the repository.
   *
   * @param jdbc the JDBC client
   * @param json the shared JSON mapper
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public CatalogRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Inserts a server.
   *
   * @param server the server
   * @param credential the sealed credential, if any
   * @param now the creation time
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void insertServer(
      CatalogServer server, Optional<CredentialCipher.Sealed> credential, Instant now) {
    jdbc.sql(
            """
            insert into upstream_server (tenant_id, id, name, transport, endpoint,
              credential_ciphertext, credential_nonce, strict, status, timeout_ms, created_at)
            values (:tenant, :id, :name, :transport, :endpoint, :cipher, :nonce, :strict, :status,
              :timeout, :now)
            """)
        .param("tenant", server.tenantId())
        .param("id", server.id())
        .param("name", server.name())
        .param("transport", server.transport())
        .param("endpoint", server.endpoint())
        .param("cipher", credential.map(CredentialCipher.Sealed::ciphertext).orElse(null))
        .param("nonce", credential.map(CredentialCipher.Sealed::nonce).orElse(null))
        .param("strict", server.strict())
        .param("status", server.status())
        .param("timeout", (int) server.timeout().toMillis())
        .param("now", now.atOffset(ZoneOffset.UTC))
        .update();
  }

  /**
   * Updates a server's settings; the credential changes only when one is given.
   *
   * @param server the new settings
   * @param credential a new sealed credential, or empty to keep the current one
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void updateServer(CatalogServer server, Optional<CredentialCipher.Sealed> credential) {
    jdbc.sql(
            """
            update upstream_server set endpoint = :endpoint, strict = :strict, status = :status,
              timeout_ms = :timeout,
              credential_ciphertext = coalesce(:cipher, credential_ciphertext),
              credential_nonce = coalesce(:nonce, credential_nonce)
            where tenant_id = :tenant and id = :id
            """)
        .param("endpoint", server.endpoint())
        .param("strict", server.strict())
        .param("status", server.status())
        .param("timeout", (int) server.timeout().toMillis())
        .param("cipher", credential.map(CredentialCipher.Sealed::ciphertext).orElse(null))
        .param("nonce", credential.map(CredentialCipher.Sealed::nonce).orElse(null))
        .param("tenant", server.tenantId())
        .param("id", server.id())
        .update();
  }

  /**
   * Lists the servers of a tenant.
   *
   * @param tenantId the tenant
   * @return the servers by name
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<CatalogServer> servers(UUID tenantId) {
    return jdbc.sql("select * from upstream_server where tenant_id = :tenant order by name")
        .param("tenant", tenantId)
        .query(this::mapServer)
        .list();
  }

  /**
   * Finds a server by id.
   *
   * @param tenantId the tenant
   * @param id the server id
   * @return the server
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<CatalogServer> server(UUID tenantId, UUID id) {
    return jdbc.sql("select * from upstream_server where tenant_id = :tenant and id = :id")
        .param("tenant", tenantId)
        .param("id", id)
        .query(this::mapServer)
        .optional();
  }

  /**
   * Finds a server by name.
   *
   * @param tenantId the tenant
   * @param name the server name
   * @return the server
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<CatalogServer> serverByName(UUID tenantId, String name) {
    return jdbc.sql("select * from upstream_server where tenant_id = :tenant and name = :name")
        .param("tenant", tenantId)
        .param("name", name)
        .query(this::mapServer)
        .optional();
  }

  /**
   * Reads a server's sealed credential.
   *
   * @param tenantId the tenant
   * @param id the server id
   * @return the ciphertext and nonce, if a credential is stored
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<CredentialCipher.Sealed> credential(UUID tenantId, UUID id) {
    return jdbc.sql(
            """
            select credential_ciphertext, credential_nonce from upstream_server
            where tenant_id = :tenant and id = :id and credential_ciphertext is not null
            """)
        .param("tenant", tenantId)
        .param("id", id)
        .query((rs, row) ->
            new CredentialCipher.Sealed(
                rs.getBytes("credential_ciphertext"), rs.getBytes("credential_nonce")))
        .optional();
  }

  /**
   * Lists the tools of one server, or of every server.
   *
   * @param tenantId the tenant
   * @param serverId the server, or empty for all
   * @return the tools by server and name
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<CatalogTool> tools(UUID tenantId, Optional<UUID> serverId) {
    return jdbc.sql(
            "select "
                + TOOL_COLUMNS
                + " where t.tenant_id = :tenant and (cast(:server as uuid) is null or t.server_id ="
                + " cast(:server as uuid)) order by s.name, t.name")
        .param("tenant", tenantId)
        .param("server", serverId.orElse(null))
        .query(this::mapTool)
        .list();
  }

  /**
   * Finds a tool by its server's name and its own name, with a single query whatever the outcome,
   * so the time taken does not tell an unknown tool from a hidden one.
   *
   * @param tenantId the tenant
   * @param serverName the server name
   * @param toolName the tool name
   * @return the tool, if it exists in any state
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<CatalogTool> tool(UUID tenantId, String serverName, String toolName) {
    return jdbc.sql(
            "select "
                + TOOL_COLUMNS
                + " where t.tenant_id = :tenant and s.name = :server and t.name = :tool")
        .param("tenant", tenantId)
        .param("server", serverName)
        .param("tool", toolName)
        .query(this::mapTool)
        .optional();
  }

  /**
   * Finds a tool by id.
   *
   * @param tenantId the tenant
   * @param id the tool id
   * @return the tool
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<CatalogTool> tool(UUID tenantId, UUID id) {
    return jdbc.sql("select " + TOOL_COLUMNS + " where t.tenant_id = :tenant and t.id = :id")
        .param("tenant", tenantId)
        .param("id", id)
        .query(this::mapTool)
        .optional();
  }

  /**
   * Inserts a newly seen tool.
   *
   * @param tool the tool
   * @param now the time
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void insertTool(CatalogTool tool, Instant now) {
    jdbc.sql(
            """
            insert into tool (tenant_id, id, server_id, name, definition, definition_sha256,
              side_effect, untrusted_source, sensitivity, cost, enabled, state, updated_at)
            values (:tenant, :id, :server, :name, cast(:definition as jsonb), :sha, :sideEffect,
              :untrusted, :sensitivity, :cost, :enabled, :state, :now)
            """)
        .param("tenant", tool.tenantId())
        .param("id", tool.id())
        .param("server", tool.serverId())
        .param("name", tool.name())
        .param("definition", json.writeValueAsString(tool.definition()))
        .param("sha", HexFormat.of().parseHex(tool.definitionSha256()))
        .param("sideEffect", tool.sideEffect())
        .param("untrusted", tool.untrustedSource())
        .param("sensitivity", tool.sensitivity())
        .param("cost", tool.cost())
        .param("enabled", tool.enabled())
        .param("state", tool.state())
        .param("now", now.atOffset(ZoneOffset.UTC))
        .update();
  }

  /**
   * Stores a changed definition for review and takes the tool out of service.
   *
   * @param tenantId the tenant
   * @param id the tool id
   * @param pending the new definition
   * @param pendingSha256 its hex hash
   * @param state the review state
   * @param now the time
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void markPending(
      UUID tenantId, UUID id, JsonNode pending, String pendingSha256, String state, Instant now) {
    jdbc.sql(
            """
            update tool set pending_definition = cast(:pending as jsonb), pending_sha256 = :sha,
              state = :state, updated_at = :now
            where tenant_id = :tenant and id = :id
            """)
        .param("pending", json.writeValueAsString(pending))
        .param("sha", HexFormat.of().parseHex(pendingSha256))
        .param("state", state)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("tenant", tenantId)
        .param("id", id)
        .update();
  }

  /**
   * Accepts the pending definition, or the current one for a new tool, and returns it to service.
   *
   * @param tenantId the tenant
   * @param id the tool id
   * @param now the time
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void acceptPending(UUID tenantId, UUID id, Instant now) {
    jdbc.sql(
            """
            update tool set definition = coalesce(pending_definition, definition),
              definition_sha256 = coalesce(pending_sha256, definition_sha256),
              pending_definition = null, pending_sha256 = null, state = 'active', updated_at = :now
            where tenant_id = :tenant and id = :id
            """)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("tenant", tenantId)
        .param("id", id)
        .update();
  }

  /**
   * Sets a tool's state without touching its definition.
   *
   * @param tenantId the tenant
   * @param id the tool id
   * @param state the new state
   * @param now the time
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void setState(UUID tenantId, UUID id, String state, Instant now) {
    jdbc.sql(
            "update tool set state = :state, updated_at = :now where tenant_id = :tenant and id ="
                + " :id")
        .param("state", state)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("tenant", tenantId)
        .param("id", id)
        .update();
  }

  /**
   * Updates the administrator's metadata of a tool.
   *
   * @param tool the tool with the new metadata
   * @param now the time
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void updateMetadata(CatalogTool tool, Instant now) {
    jdbc.sql(
            """
            update tool set side_effect = :sideEffect, untrusted_source = :untrusted,
              sensitivity = :sensitivity, cost = :cost, enabled = :enabled, updated_at = :now
            where tenant_id = :tenant and id = :id
            """)
        .param("sideEffect", tool.sideEffect())
        .param("untrusted", tool.untrustedSource())
        .param("sensitivity", tool.sensitivity())
        .param("cost", tool.cost())
        .param("enabled", tool.enabled())
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("tenant", tool.tenantId())
        .param("id", tool.id())
        .update();
  }

  /**
   * Maps a server row.
   *
   * @param rs the result set
   * @param row the row number
   * @return the server
   * @throws SQLException if a column cannot be read
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private CatalogServer mapServer(ResultSet rs, int row) throws SQLException {
    return new CatalogServer(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("transport"),
        rs.getString("endpoint"),
        rs.getBoolean("strict"),
        rs.getString("status"),
        Duration.ofMillis(rs.getInt("timeout_ms")),
        rs.getBytes("credential_ciphertext") != null);
  }

  /**
   * Maps a tool row joined with its server's name.
   *
   * @param rs the result set
   * @param row the row number
   * @return the tool
   * @throws SQLException if a column cannot be read
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private CatalogTool mapTool(ResultSet rs, int row) throws SQLException {
    String pending = rs.getString("pending_definition");
    return new CatalogTool(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("id", UUID.class),
        rs.getObject("server_id", UUID.class),
        rs.getString("server_name"),
        rs.getString("name"),
        json.readTree(rs.getString("definition")),
        HexFormat.of().formatHex(rs.getBytes("definition_sha256")),
        Optional.ofNullable(pending).map(json::readTree),
        rs.getBoolean("side_effect"),
        rs.getBoolean("untrusted_source"),
        rs.getString("sensitivity"),
        rs.getInt("cost"),
        rs.getBoolean("enabled"),
        rs.getString("state"));
  }
}
