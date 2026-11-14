// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.events;

import io.github.rnachoc.aduana.gateway.audit.AuditLog;
import io.github.rnachoc.aduana.gateway.audit.AuditRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Records security and administration events in the hash-chained audit log. Details never hold
 * secrets or raw arguments; they land in the entry's `args_redacted` field.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class EventLog {

  /**
   * One event.
   *
   * @param type the entry type from the closed list of plan §6.3
   * @param actorId the console user who caused it, if any
   * @param detail non-sensitive details
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Event(String type, Optional<UUID> actorId, Map<String, String> detail) {}

  private final AuditLog audit;
  private final JdbcClient jdbc;
  private final JsonMapper json;

  /**
   * Creates the event log.
   *
   * @param audit the chained log
   * @param jdbc the JDBC client, for reading recent types
   * @param json the shared JSON mapper
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public EventLog(AuditLog audit, JdbcClient jdbc, JsonMapper json) {
    this.audit = audit;
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Appends an event to the chain.
   *
   * @param tenantId the tenant
   * @param event the event
   * @throws org.springframework.dao.DataAccessException if the log cannot be written, so the change
   *     it describes is rolled back
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void record(UUID tenantId, Event event) {
    ObjectNode details = json.createObjectNode();
    new TreeMap<>(event.detail()).forEach(details::put);
    AuditRecord.Builder record = AuditRecord.of(event.type()).details(details);
    event.actorId().ifPresent(record::actor);
    audit.append(tenantId, record.build());
  }

  /**
   * Lists the types of the most recent entries other than checkpoints, newest first.
   *
   * @param tenantId the tenant
   * @param limit how many to return
   * @return entry types
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<String> recentTypes(UUID tenantId, int limit) {
    return jdbc.sql(
            """
            select type from audit_entry where tenant_id = :tenant and type <> 'CHECKPOINT'
            order by seq desc limit :limit
            """)
        .param("tenant", tenantId)
        .param("limit", limit)
        .query(String.class)
        .list();
  }
}
