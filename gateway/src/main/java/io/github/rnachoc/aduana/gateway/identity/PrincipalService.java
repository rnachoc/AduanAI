// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.identity;

import io.github.rnachoc.aduana.gateway.events.EventLog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates principals, issues and revokes their API keys, and authenticates keys.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Service
public class PrincipalService {

  private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

  private final PrincipalRepository repository;
  private final EventLog events;
  private final Clock clock;

  /**
   * A key as returned once at creation.
   *
   * @param key the metadata
   * @param secret the full key; it is not stored and cannot be shown again
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record IssuedKey(ApiKey key, String secret) {}

  /**
   * Creates the service.
   *
   * @param repository principal storage
   * @param events the event log
   * @param clock the clock
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public PrincipalService(PrincipalRepository repository, EventLog events, Clock clock) {
    this.repository = repository;
    this.events = events;
    this.clock = clock;
  }

  /**
   * Creates a principal.
   *
   * @param tenantId the tenant
   * @param kind agent or user
   * @param name a unique name: lowercase letters, digits, dot, dash or underscore
   * @param attributes attributes policies match on
   * @param groups groups such as approver groups
   * @return the new principal
   * @throws IllegalArgumentException if the name is invalid or taken
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Transactional
  public Principal create(
      UUID tenantId,
      Principal.Kind kind,
      String name,
      Map<String, String> attributes,
      List<String> groups) {
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Principal names use a-z, 0-9, '.', '-' and '_'");
    }
    if (repository.findByName(tenantId, name).isPresent()) {
      throw new IllegalArgumentException("A principal with that name exists");
    }
    Principal principal =
        new Principal(tenantId, UUID.randomUUID(), kind, name, attributes, groups, "active");
    repository.insert(principal, clock.instant());
    return principal;
  }

  /**
   * Issues a key for a principal.
   *
   * @param tenantId the tenant
   * @param principalId the principal
   * @param validity how long the key works; empty for no expiry
   * @param actorId the console user who asked for it
   * @return the key, including the secret that is shown once
   * @throws IllegalArgumentException if the principal does not exist
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Transactional
  public IssuedKey issueKey(
      UUID tenantId, UUID principalId, Optional<Duration> validity, Optional<UUID> actorId) {
    repository
        .find(tenantId, principalId)
        .orElseThrow(() -> new IllegalArgumentException("No such principal"));
    String secret = ApiKeys.generate();
    Instant now = clock.instant();
    ApiKey key =
        new ApiKey(
            tenantId,
            UUID.randomUUID(),
            principalId,
            ApiKeys.visiblePrefix(secret),
            now,
            validity.map(now::plus),
            Optional.empty());
    repository.insertKey(key, ApiKeys.sha256(secret));
    events.record(
        tenantId,
        new EventLog.Event(
            "KEY_CREATED",
            actorId,
            Map.of("key_id", key.id().toString(), "principal_id", principalId.toString())));
    return new IssuedKey(key, secret);
  }

  /**
   * Revokes a key.
   *
   * @param tenantId the tenant
   * @param keyId the key
   * @param actorId the console user who revoked it
   * @return true if the key existed and was active
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Transactional
  public boolean revokeKey(UUID tenantId, UUID keyId, Optional<UUID> actorId) {
    boolean revoked = repository.revokeKey(tenantId, keyId, clock.instant());
    if (revoked) {
      events.record(
          tenantId, new EventLog.Event("KEY_REVOKED", actorId, Map.of("key_id", keyId.toString())));
    }
    return revoked;
  }

  /**
   * Finds the principal a presented key belongs to.
   *
   * @param presented the bearer value
   * @return the active principal, if the key is valid, unexpired and not revoked
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<Principal> authenticate(String presented) {
    if (!presented.startsWith(ApiKeys.PREFIX)) {
      return Optional.empty();
    }
    return repository.findByKeyHash(ApiKeys.sha256(presented), clock.instant());
  }

  /**
   * Finds a principal by name.
   *
   * @param tenantId the tenant
   * @param name the name
   * @return the principal, if any
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<Principal> findByName(UUID tenantId, String name) {
    return repository.findByName(tenantId, name);
  }

  /**
   * Lists the principals of a tenant.
   *
   * @param tenantId the tenant
   * @return the principals
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<Principal> list(UUID tenantId) {
    return repository.list(tenantId);
  }

  /**
   * Lists the keys of a principal.
   *
   * @param tenantId the tenant
   * @param principalId the principal
   * @return key metadata, newest first
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<ApiKey> keys(UUID tenantId, UUID principalId) {
    return repository.listKeys(tenantId, principalId);
  }
}
