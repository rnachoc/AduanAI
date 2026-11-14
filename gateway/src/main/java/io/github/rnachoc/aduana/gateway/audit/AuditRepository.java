// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.audit;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Explicit SQL for the audit tables. It only ever inserts and reads: the application role has no
 * other privilege on them, and a trigger refuses the rest even to the owner.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Repository
public class AuditRepository {

  /** Every field of an entry, in the order plan §5.5 lists them. */
  static final List<String> FIELDS =
      List.of(
          "seq",
          "tenant_id",
          "ts",
          "type",
          "principal_id",
          "server",
          "tool",
          "args_hash",
          "args_redacted",
          "decision",
          "rule_ids",
          "reason_code",
          "policy_bundle_hash",
          "approval_id",
          "actor_id",
          "result_hash",
          "flags",
          "latency_ms",
          "prev_hash",
          "hash");

  private final JdbcClient jdbc;
  private final JsonMapper json;

  /**
   * The newest entry of a tenant.
   *
   * @param seq its sequence number
   * @param hash its hash
   * @param ts its timestamp
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Head(long seq, String hash, String ts) {}

  /**
   * Creates the repository.
   *
   * @param jdbc the JDBC client
   * @param json the shared JSON mapper
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public AuditRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Serialises appends for one tenant until the transaction ends, so two writers can never pick the
   * same `seq` or fork the chain.
   *
   * @param tenantId the tenant
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void lock(UUID tenantId) {
    jdbc.sql("select pg_advisory_xact_lock(hashtextextended(cast(:tenant as text), 7))")
        .param("tenant", tenantId)
        .query((rs, row) -> 0)
        .single();
  }

  /**
   * Reads the newest entry.
   *
   * @param tenantId the tenant
   * @return its seq, hash and time, or empty for an empty log
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<Head> head(UUID tenantId) {
    return jdbc.sql(
            "select seq, hash, ts from audit_entry where tenant_id = :tenant order by seq desc limit 1")
        .param("tenant", tenantId)
        .query((rs, row) -> new Head(rs.getLong("seq"), rs.getString("hash"), rs.getString("ts")))
        .optional();
  }

  /**
   * Inserts a hashed entry exactly as it was hashed.
   *
   * @param entry the entry with every field
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void insert(ObjectNode entry) {
    jdbc.sql(
            """
            insert into audit_entry (tenant_id, seq, ts, type, principal_id, server, tool, args_hash,
              args_redacted, decision, rule_ids, reason_code, policy_bundle_hash, approval_id, actor_id,
              result_hash, flags, latency_ms, prev_hash, hash)
            values (cast(:tenant as uuid), :seq, :ts, :type, cast(:principal as uuid), :server, :tool,
              :argsHash, cast(:args as jsonb), :decision, :rules, :reason, :bundle,
              cast(:approval as uuid), cast(:actor as uuid), :resultHash, :flags, :latency, :prev, :hash)
            """)
        .param("tenant", entry.get("tenant_id").asString())
        .param("seq", entry.get("seq").asLong())
        .param("ts", entry.get("ts").asString())
        .param("type", entry.get("type").asString())
        .param("principal", text(entry, "principal_id"))
        .param("server", text(entry, "server"))
        .param("tool", text(entry, "tool"))
        .param("argsHash", text(entry, "args_hash"))
        .param(
            "args",
            entry.get("args_redacted").isNull() ? null : entry.get("args_redacted").toString())
        .param("decision", text(entry, "decision"))
        .param("rules", strings(entry.get("rule_ids")))
        .param("reason", text(entry, "reason_code"))
        .param("bundle", entry.get("policy_bundle_hash").asString())
        .param("approval", text(entry, "approval_id"))
        .param("actor", text(entry, "actor_id"))
        .param("resultHash", text(entry, "result_hash"))
        .param("flags", strings(entry.get("flags")))
        .param(
            "latency", entry.get("latency_ms").isNull() ? null : entry.get("latency_ms").asLong())
        .param("prev", entry.get("prev_hash").asString())
        .param("hash", entry.get("hash").asString())
        .update();
  }

  /**
   * Inserts a signed checkpoint.
   *
   * @param checkpoint the checkpoint as exported
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void insertCheckpoint(ObjectNode checkpoint) {
    jdbc.sql(
            """
            insert into audit_checkpoint (tenant_id, seq, hash, ts, signature, key_id)
            values (cast(:tenant as uuid), :seq, :hash, :ts, :signature, :key)
            """)
        .param("tenant", checkpoint.get("tenant_id").asString())
        .param("seq", checkpoint.get("seq").asLong())
        .param("hash", checkpoint.get("hash").asString())
        .param("ts", checkpoint.get("ts").asString())
        .param("signature", checkpoint.get("signature").asString())
        .param("key", checkpoint.get("key_id").asString())
        .update();
  }

  /**
   * Reads entries in `seq` order with optional filters.
   *
   * @param tenantId the tenant
   * @param filter the filters and page
   * @return the entries as JSON, exactly as hashed
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<ObjectNode> entries(UUID tenantId, AuditFilter filter) {
    return jdbc.sql(
            """
            select * from audit_entry where tenant_id = :tenant and seq >= :from
              and (cast(:principal as uuid) is null or principal_id = cast(:principal as uuid))
              and (cast(:tool as text) is null or tool = cast(:tool as text))
              and (cast(:decision as text) is null or decision = cast(:decision as text))
              and (cast(:fromTs as text) is null or ts >= cast(:fromTs as text))
              and (cast(:toTs as text) is null or ts <= cast(:toTs as text))
            order by seq limit :limit
            """)
        .param("tenant", tenantId)
        .param("from", filter.fromSeq())
        .param("principal", filter.principal().orElse(null))
        .param("tool", filter.tool().orElse(null))
        .param("decision", filter.decision().orElse(null))
        .param("fromTs", filter.fromTs().orElse(null))
        .param("toTs", filter.toTs().orElse(null))
        .param("limit", filter.limit())
        .query(this::map)
        .list();
  }

  /**
   * Reads every checkpoint of a tenant.
   *
   * @param tenantId the tenant
   * @return the checkpoints in `seq` order
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<ObjectNode> checkpoints(UUID tenantId) {
    return jdbc.sql("select * from audit_checkpoint where tenant_id = :tenant order by seq")
        .param("tenant", tenantId)
        .query((rs, row) -> {
          ObjectNode node = json.createObjectNode();
          node.put("tenant_id", rs.getObject("tenant_id", UUID.class).toString());
          node.put("seq", rs.getLong("seq"));
          node.put("hash", rs.getString("hash"));
          node.put("ts", rs.getString("ts"));
          node.put("signature", rs.getString("signature"));
          node.put("key_id", rs.getString("key_id"));
          return node;
        })
        .list();
  }

  /**
   * Returns the seq of the newest checkpoint.
   *
   * @param tenantId the tenant
   * @return the seq, or 0 when there is none
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public long lastCheckpointSeq(UUID tenantId) {
    return jdbc.sql("select coalesce(max(seq), 0) from audit_checkpoint where tenant_id = :tenant")
        .param("tenant", tenantId)
        .query(Long.class)
        .single();
  }

  /**
   * Rebuilds an entry's JSON from its row, with every field present.
   *
   * @param rs the result set
   * @param row the row number
   * @return the entry
   * @throws SQLException if a column cannot be read
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ObjectNode map(ResultSet rs, int row) throws SQLException {
    ObjectNode entry = json.createObjectNode();
    entry.put("seq", rs.getLong("seq"));
    entry.put("tenant_id", rs.getObject("tenant_id", UUID.class).toString());
    entry.put("ts", rs.getString("ts"));
    entry.put("type", rs.getString("type"));
    entry.put("principal_id", nullableUuid(rs, "principal_id"));
    entry.put("server", rs.getString("server"));
    entry.put("tool", rs.getString("tool"));
    entry.put("args_hash", rs.getString("args_hash"));
    String args = rs.getString("args_redacted");
    entry.set("args_redacted", args == null ? json.nullNode() : json.readTree(args));
    entry.put("decision", rs.getString("decision"));
    entry.set("rule_ids", array(rs.getArray("rule_ids")));
    entry.put("reason_code", rs.getString("reason_code"));
    entry.put("policy_bundle_hash", rs.getString("policy_bundle_hash"));
    entry.put("approval_id", nullableUuid(rs, "approval_id"));
    entry.put("actor_id", nullableUuid(rs, "actor_id"));
    entry.put("result_hash", rs.getString("result_hash"));
    entry.set("flags", array(rs.getArray("flags")));
    long latency = rs.getLong("latency_ms");
    if (rs.wasNull()) {
      entry.putNull("latency_ms");
    } else {
      entry.put("latency_ms", latency);
    }
    entry.put("prev_hash", rs.getString("prev_hash"));
    entry.put("hash", rs.getString("hash"));
    return entry;
  }

  /**
   * Reads a nullable uuid column as text.
   *
   * @param rs the result set
   * @param column the column
   * @return the uuid text, or null
   * @throws SQLException if the column cannot be read
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String nullableUuid(ResultSet rs, String column) throws SQLException {
    UUID value = rs.getObject(column, UUID.class);
    return value == null ? null : value.toString();
  }

  /**
   * Turns a SQL text array into a JSON array.
   *
   * @param array the array
   * @return the JSON array
   * @throws SQLException if the array cannot be read
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ArrayNode array(Array array) throws SQLException {
    ArrayNode node = json.createArrayNode();
    for (String value : (String[]) array.getArray()) {
      node.add(value);
    }
    return node;
  }

  /**
   * Reads a nullable text field of an entry.
   *
   * @param entry the entry
   * @param field the field
   * @return the text, or null
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String text(ObjectNode entry, String field) {
    return entry.get(field).isNull() ? null : entry.get(field).asString();
  }

  /**
   * Turns a JSON array of strings into a Java array for a SQL text array.
   *
   * @param array the JSON array
   * @return the strings
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String[] strings(tools.jackson.databind.JsonNode array) {
    return array
        .valueStream()
        .map(tools.jackson.databind.JsonNode::asString)
        .toArray(String[]::new);
  }
}
