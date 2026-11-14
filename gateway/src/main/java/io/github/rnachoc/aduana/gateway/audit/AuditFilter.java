// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.audit;

import java.util.Optional;
import java.util.UUID;

/**
 * Which entries to read: a page by `seq` and optional filters.
 *
 * @param fromSeq the first seq to return
 * @param limit the most entries to return
 * @param principal only entries about this principal
 * @param tool only entries about this agent-facing tool name
 * @param decision only entries with this decision
 * @param fromTs only entries at or after this RFC 3339 time
 * @param toTs only entries at or before this RFC 3339 time
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record AuditFilter(
    long fromSeq,
    int limit,
    Optional<UUID> principal,
    Optional<String> tool,
    Optional<String> decision,
    Optional<String> fromTs,
    Optional<String> toTs) {

  /** The most entries one read returns. */
  public static final int MAX_LIMIT = 1_000_000;

  /**
   * Every entry of a tenant.
   *
   * @return the filter
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static AuditFilter all() {
    return new AuditFilter(
        1,
        MAX_LIMIT,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }
}
