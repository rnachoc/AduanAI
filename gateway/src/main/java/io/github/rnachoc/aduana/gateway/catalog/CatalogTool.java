// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * A tool as pinned in the catalog: the definition the server published when it was last accepted,
 * and the metadata the administrator added.
 *
 * @param tenantId the tenant
 * @param id the tool id
 * @param serverId the server
 * @param serverName the server's name
 * @param name the tool's name on its server
 * @param definition the accepted definition (name, description, schemas, annotations)
 * @param definitionSha256 hex SHA-256 of the accepted definition's canonical form
 * @param pendingDefinition a changed definition waiting for review
 * @param sideEffect whether the tool changes something outside the gateway
 * @param untrustedSource whether its results come from an untrusted place (web, mail)
 * @param sensitivity low, medium or high
 * @param cost budget units charged per call
 * @param enabled whether the administrator allows it at all
 * @param state active, changed_pending_review, new_pending_review or removed
 * @param descriptionFlags injection patterns found in the published description
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record CatalogTool(
    UUID tenantId,
    UUID id,
    UUID serverId,
    String serverName,
    String name,
    JsonNode definition,
    String definitionSha256,
    Optional<JsonNode> pendingDefinition,
    boolean sideEffect,
    boolean untrustedSource,
    String sensitivity,
    int cost,
    boolean enabled,
    String state,
    List<String> descriptionFlags) {

  /**
   * Tells whether agents may see and call the tool.
   *
   * @return true when enabled and in the active state
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public boolean isOffered() {
    return enabled && "active".equals(state);
  }
}
