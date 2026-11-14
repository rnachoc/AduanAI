// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.events;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records security and administration events in a plain table until the hash-chained audit log
 * replaces it in Phase 5. Details never hold secrets or raw arguments.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Repository
public class EventLog {

  /**
   * One recorded event.
   *
   * @param type the event type from the closed list of plan §6.3
   * @param principalId who caused it, if known
   * @param detail non-sensitive details
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Event(String type, Optional<UUID> principalId, Map<String, String> detail) {}

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final Clock clock;

  /**
   * Creates the log.
   *
   * @param jdbc the JDBC client
   * @param json the shared JSON mapper
   * @param clock the clock
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public EventLog(JdbcClient jdbc, JsonMapper json, Clock clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
  }

  /**
   * Appends an event.
   *
   * @param tenantId the tenant
   * @param event the event
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void record(UUID tenantId, Event event) {
    jdbc.sql(
            """
            insert into event_log (tenant_id, ts, type, principal_id, detail)
            values (:tenant, :ts, :type, :principal, cast(:detail as jsonb))
            """)
        .param("tenant", tenantId)
        .param("ts", clock.instant().atOffset(ZoneOffset.UTC))
        .param("type", event.type())
        .param("principal", event.principalId().orElse(null))
        .param("detail", json.writeValueAsString(event.detail()))
        .update();
  }

  /**
   * Lists the types of the most recent events, newest first, for tests and the console.
   *
   * @param tenantId the tenant
   * @param limit how many to return
   * @return event types
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<String> recentTypes(UUID tenantId, int limit) {
    return jdbc.sql(
            "select type from event_log where tenant_id = :tenant order by id desc limit :limit")
        .param("tenant", tenantId)
        .param("limit", limit)
        .query(String.class)
        .list();
  }
}
