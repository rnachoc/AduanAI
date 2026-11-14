// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.identity;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An identity that calls tools (an agent) or uses the console (a user).
 *
 * @param tenantId the tenant
 * @param id the principal id
 * @param kind agent or user
 * @param name a unique, human-readable name
 * @param attributes free key-value attributes policies match on, such as team or env
 * @param groups groups the principal belongs to, such as an approvers group
 * @param status active or disabled
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record Principal(
    UUID tenantId,
    UUID id,
    Kind kind,
    String name,
    Map<String, String> attributes,
    List<String> groups,
    String status) {

  /**
   * Whether the principal is an agent or a person.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public enum Kind {
    /** Calls tools through the MCP endpoint; can never approve. */
    AGENT,
    /** Uses the console. */
    USER
  }

  /**
   * Copies the collections so a principal cannot change after it is read.
   *
   * @param tenantId the tenant
   * @param id the principal id
   * @param kind agent or user
   * @param name the name
   * @param attributes the attributes
   * @param groups the groups
   * @param status the status
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Principal {
    Objects.requireNonNull(tenantId, "tenantId");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(name, "name");
    attributes = Map.copyOf(attributes);
    groups = List.copyOf(groups);
    Objects.requireNonNull(status, "status");
  }

  /**
   * Tells whether the principal may act.
   *
   * @return true when active
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public boolean isActive() {
    return "active".equals(status);
  }
}
