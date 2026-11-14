// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.limits;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes a retried call with the same idempotency key run once (plan §5.8).
 *
 * <p>The specification defines no idempotency field (ADR 1), so the key travels in
 * `_meta["aduana/idempotency-key"]`. The first call claims the key with an insert that only one
 * caller can win; a retry with the same arguments gets the stored result, one with other arguments
 * gets `IDEMPOTENCY_CONFLICT`, and one that arrives while the first is still running waits for it.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Service
public class IdempotencyService {

  /** The `_meta` key that carries the idempotency key. */
  public static final String META_KEY = "aduana/idempotency-key";

  private static final Duration POLL = Duration.ofMillis(200);

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final Clock clock;
  private final Duration retention;
  private final Duration wait;

  /**
   * What to do with a call that carries a key.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public sealed interface Claim {

    /**
     * This call owns the key: run it and store its result.
     *
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    record Owner() implements Claim {}

    /**
     * An earlier call with the same key and arguments finished: answer with its result.
     *
     * @param result the stored result
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    record Replay(JsonNode result) implements Claim {}

    /**
     * The key was used with other arguments, or the first call never finished in time.
     *
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    record Conflict() implements Claim {}
  }

  /**
   * Creates the service.
   *
   * @param jdbc the JDBC client
   * @param json the shared JSON mapper
   * @param clock the clock
   * @param retention how long a key is remembered
   * @param wait how long a retry waits for a first call still running
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public IdempotencyService(
      JdbcClient jdbc,
      JsonMapper json,
      Clock clock,
      @Value("${aduana.idempotency.retention:PT24H}") Duration retention,
      @Value("${aduana.idempotency.wait:PT10S}") Duration wait) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
    this.retention = retention;
    this.wait = wait;
  }

  /**
   * Claims a key for a call.
   *
   * @param tenantId the tenant
   * @param principalId the agent; keys are scoped to it
   * @param key the idempotency key
   * @param argsHash the hash of the arguments
   * @return owner, replay or conflict
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Claim claim(UUID tenantId, UUID principalId, String key, String argsHash) {
    Instant now = clock.instant();
    try {
      jdbc.sql(
              """
              insert into idempotency_record (tenant_id, principal_id, key, args_hash, created_at, expires_at)
              values (:tenant, :principal, :key, :hash, :now, :expires)
              """)
          .param("tenant", tenantId)
          .param("principal", principalId)
          .param("key", key)
          .param("hash", argsHash)
          .param("now", now.atOffset(ZoneOffset.UTC))
          .param("expires", now.plus(retention).atOffset(ZoneOffset.UTC))
          .update();
      return new Claim.Owner();
    } catch (DuplicateKeyException exception) {
      return existing(tenantId, principalId, key, argsHash);
    }
  }

  /**
   * Stores the result of the call that owns a key.
   *
   * @param tenantId the tenant
   * @param principalId the agent
   * @param key the key
   * @param result the result the agent received
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void complete(UUID tenantId, UUID principalId, String key, JsonNode result) {
    jdbc.sql(
            """
            update idempotency_record set result = cast(:result as jsonb)
            where tenant_id = :tenant and principal_id = :principal and key = :key
            """)
        .param("result", json.writeValueAsString(result))
        .param("tenant", tenantId)
        .param("principal", principalId)
        .param("key", key)
        .update();
  }

  /**
   * Releases a key whose call did not produce a result, so a retry can run it.
   *
   * @param tenantId the tenant
   * @param principalId the agent
   * @param key the key
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void release(UUID tenantId, UUID principalId, String key) {
    jdbc.sql(
            """
            delete from idempotency_record
            where tenant_id = :tenant and principal_id = :principal and key = :key and result is null
            """)
        .param("tenant", tenantId)
        .param("principal", principalId)
        .param("key", key)
        .update();
  }

  /**
   * Resolves a key that is already taken, waiting for a first call still in progress.
   *
   * @param tenantId the tenant
   * @param principalId the agent
   * @param key the key
   * @param argsHash the retry's arguments hash
   * @return replay or conflict
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Claim existing(UUID tenantId, UUID principalId, String key, String argsHash) {
    Instant deadline = clock.instant().plus(wait);
    while (true) {
      Optional<Stored> stored =
          jdbc.sql(
                  """
                  select args_hash, result from idempotency_record
                  where tenant_id = :tenant and principal_id = :principal and key = :key
                  """)
              .param("tenant", tenantId)
              .param("principal", principalId)
              .param("key", key)
              .query((rs, row) ->
                  new Stored(
                      rs.getString("args_hash"), Optional.ofNullable(rs.getString("result"))))
              .optional();
      if (stored.isEmpty()) {
        return claim(tenantId, principalId, key, argsHash);
      }
      if (!stored.get().argsHash().equals(argsHash)) {
        return new Claim.Conflict();
      }
      if (stored.get().result().isPresent()) {
        return new Claim.Replay(json.readTree(stored.get().result().get()));
      }
      if (clock.instant().isAfter(deadline)) {
        return new Claim.Conflict();
      }
      try {
        Thread.sleep(POLL);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return new Claim.Conflict();
      }
    }
  }

  /**
   * A stored key.
   *
   * @param argsHash the arguments it was claimed with
   * @param result the stored result, once there is one
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private record Stored(String argsHash, Optional<String> result) {}

  /**
   * Forgets expired keys.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Scheduled(fixedDelayString = "${aduana.idempotency.cleanup-interval:PT1H}")
  public void deleteExpired() {
    jdbc.sql("delete from idempotency_record where expires_at <= :now")
        .param("now", clock.instant().atOffset(ZoneOffset.UTC))
        .update();
  }
}
