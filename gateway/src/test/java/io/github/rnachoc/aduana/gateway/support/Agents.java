// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.support;

import io.github.rnachoc.aduana.gateway.identity.Principal;
import io.github.rnachoc.aduana.gateway.identity.PrincipalService;
import io.github.rnachoc.aduana.gateway.identity.Tenants;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates agent principals with keys for tests.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class Agents {

  /**
   * Prevents instances: the class only has a static helper.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Agents() {}

  /**
   * Creates an agent with a unique name and the given attributes, and returns a fresh key for it.
   *
   * @param principals the principal service
   * @param attributes the agent's attributes
   * @return the API key
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static String agentKey(PrincipalService principals, Map<String, String> attributes) {
    Principal agent =
        principals.create(
            Tenants.DEFAULT,
            Principal.Kind.AGENT,
            "agent-" + UUID.randomUUID().toString().substring(0, 8),
            attributes,
            List.of());
    return principals
        .issueKey(Tenants.DEFAULT, agent.id(), Optional.empty(), Optional.empty())
        .secret();
  }
}
