// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.identity;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

/**
 * A person who signs in to the console, with one role.
 *
 * <p>Serializable because it lives in the HTTP session.
 *
 * @param tenantId the tenant
 * @param id the console user id
 * @param principalId the user's principal, the identity recorded in the log
 * @param email the sign-in address
 * @param role what the user may do
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record ConsoleUser(UUID tenantId, UUID id, UUID principalId, String email, Role role)
    implements Serializable {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Console roles. Each includes the ones below it: admin, then approver, then viewer.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public enum Role {
    /** Reads activity and the log. */
    VIEWER,
    /** Also decides approvals. */
    APPROVER,
    /** Also manages the catalog, identities and policy. */
    ADMIN;

    /**
     * Tells whether this role includes another.
     *
     * @param other the role required
     * @return true when this role is the same or higher
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public boolean includes(Role other) {
      return compareTo(other) >= 0;
    }
  }
}
