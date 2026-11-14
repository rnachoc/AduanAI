// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.inspection;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Remembers, for a while, the hashes of values an agent received from untrusted tools, and spots
 * them when they come back as arguments (plan §5.6). Stores hashes only, never values.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Service
public class TaintService {

  private final JdbcClient jdbc;
  private final Clock clock;
  private final Duration window;

  /**
   * Where a tainted argument value came from.
   *
   * @param path the argument's JSONPath
   * @param sourceSeq the seq of the entry that recorded the untrusted result
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Source(String path, long sourceSeq) {}

  /**
   * The taint marks of a call.
   *
   * @param flowFromUntrusted an argument holds a value from an untrusted result
   * @param contextTainted the principal received untrusted data within the window
   * @param sources where each tainted argument came from
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Marks(boolean flowFromUntrusted, boolean contextTainted, List<Source> sources) {}

  /**
   * Creates the service.
   *
   * @param jdbc the JDBC client
   * @param clock the clock
   * @param window how long a value stays tainted
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public TaintService(
      JdbcClient jdbc, Clock clock, @Value("${aduana.taint.window:PT30M}") Duration window) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.window = window;
  }

  /**
   * Records the values of an untrusted result.
   *
   * @param tenantId the tenant
   * @param principalId the agent that received them
   * @param traceKey the trace id from `_meta.traceparent`, or empty
   * @param result the tool result
   * @param sourceSeq the seq of the entry that recorded the result
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void record(
      UUID tenantId, UUID principalId, String traceKey, JsonNode result, long sourceSeq) {
    Instant expires = clock.instant().plus(window);
    for (String hash : TraceableValues.hashes(result).keySet()) {
      jdbc.sql(
              """
              insert into taint_token (tenant_id, principal_id, trace_key, value_sha256, source_seq, expires_at)
              values (:tenant, :principal, :trace, :hash, :seq, :expires)
              """)
          .param("tenant", tenantId)
          .param("principal", principalId)
          .param("trace", traceKey)
          .param("hash", hash)
          .param("seq", sourceSeq)
          .param("expires", expires.atOffset(ZoneOffset.UTC))
          .update();
    }
  }

  /**
   * Computes the taint marks of a call's arguments.
   *
   * <p>A call with a trace id matches tokens of the same trace and tokens recorded without one; a
   * call without a trace id matches every token of the principal within the window.
   *
   * @param tenantId the tenant
   * @param principalId the agent
   * @param traceKey the call's trace id, or empty
   * @param arguments the arguments
   * @return the marks
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Marks check(UUID tenantId, UUID principalId, String traceKey, JsonNode arguments) {
    String scope =
        """
        tenant_id = :tenant and principal_id = :principal and expires_at > :now
          and (:trace = '' or trace_key = '' or trace_key = :trace)
        """;
    boolean tainted =
        jdbc.sql("select exists (select 1 from taint_token where " + scope + ")")
            .param("tenant", tenantId)
            .param("principal", principalId)
            .param("now", clock.instant().atOffset(ZoneOffset.UTC))
            .param("trace", traceKey)
            .query(Boolean.class)
            .single();
    List<Source> sources = new ArrayList<>();
    if (tainted) {
      for (Map.Entry<String, String> value : TraceableValues.hashes(arguments).entrySet()) {
        jdbc.sql(
                "select min(source_seq) from taint_token where "
                    + scope
                    + " and value_sha256 = :hash")
            .param("tenant", tenantId)
            .param("principal", principalId)
            .param("now", clock.instant().atOffset(ZoneOffset.UTC))
            .param("trace", traceKey)
            .param("hash", value.getKey())
            .query(Long.class)
            .optional()
            .map(seq -> new Source(value.getValue(), seq))
            .filter(source -> !sources.contains(source))
            .ifPresent(sources::add);
      }
    }
    return new Marks(!sources.isEmpty(), tainted, List.copyOf(sources));
  }

  /**
   * Deletes expired tokens every few minutes.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Scheduled(fixedDelayString = "${aduana.taint.cleanup-interval:PT5M}")
  public void deleteExpired() {
    jdbc.sql("delete from taint_token where expires_at <= :now")
        .param("now", clock.instant().atOffset(ZoneOffset.UTC))
        .update();
  }
}
