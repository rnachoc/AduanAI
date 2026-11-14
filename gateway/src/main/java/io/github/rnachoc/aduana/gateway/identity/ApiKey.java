// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * An API key's metadata. The key itself is shown once at creation and never stored.
 *
 * @param tenantId the tenant
 * @param id the key id
 * @param principalId the principal the key authenticates
 * @param prefix the visible start of the key, to tell keys apart
 * @param createdAt when the key was created
 * @param expiresAt when the key stops working, if ever
 * @param revokedAt when the key was revoked, if it was
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record ApiKey(
    UUID tenantId,
    UUID id,
    UUID principalId,
    String prefix,
    Instant createdAt,
    Optional<Instant> expiresAt,
    Optional<Instant> revokedAt) {}
