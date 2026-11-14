// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.catalog;

import io.github.rnachoc.aduana.gateway.upstream.UpstreamServer;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * An upstream server as stored in the catalog. The credential is not part of it.
 *
 * @param tenantId the tenant
 * @param id the server id
 * @param name the name, prefix of its tools
 * @param transport http or stdio
 * @param endpoint the URL (http) or the command (stdio)
 * @param strict whether arguments outside the input schema's properties are refused
 * @param status active or disabled
 * @param timeout the call timeout
 * @param hasCredential whether a credential is stored for injection
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record CatalogServer(
    UUID tenantId,
    UUID id,
    String name,
    String transport,
    String endpoint,
    boolean strict,
    String status,
    Duration timeout,
    boolean hasCredential) {

  /**
   * Tells whether the gateway may forward to the server.
   *
   * @return true when active
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public boolean isActive() {
    return "active".equals(status);
  }

  /**
   * Returns the connection settings the upstream client needs.
   *
   * @return the upstream
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public UpstreamServer upstream() {
    return new UpstreamServer(name, URI.create(endpoint), timeout);
  }
}
