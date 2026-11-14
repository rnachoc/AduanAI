// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.security;

import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpMethod;

/**
 * The one table that says who may call each route (plan §6.2). A route missing here is denied, and
 * a test fails if a controller declares a route this table does not list.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class RouteAccess {

  /**
   * Who may call a route.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public enum Access {
    /** Anyone, without signing in. */
    PUBLIC,
    /** An agent with an API key or JWT (MCP endpoint). */
    AGENT,
    /** Any signed-in console user. */
    SIGNED_IN,
    /** Console users with the viewer role or higher. */
    VIEWER,
    /** Console users with the approver role or higher. */
    APPROVER,
    /** Console administrators. */
    ADMIN
  }

  /**
   * One row: method, path pattern and access.
   *
   * @param method the HTTP method
   * @param pattern the Spring path pattern, as the controller declares it
   * @param access who may call it
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Route(HttpMethod method, String pattern, Access access) {}

  /** Every route of the gateway. */
  public static final List<Route> ROUTES =
      List.of(
          new Route(HttpMethod.GET, "/healthz", Access.PUBLIC),
          new Route(HttpMethod.POST, "/mcp", Access.AGENT),
          new Route(HttpMethod.GET, "/mcp", Access.PUBLIC),
          new Route(HttpMethod.DELETE, "/mcp", Access.PUBLIC),
          new Route(HttpMethod.POST, "/api/v1/auth/login", Access.PUBLIC),
          new Route(HttpMethod.POST, "/api/v1/auth/logout", Access.SIGNED_IN),
          new Route(HttpMethod.GET, "/api/v1/auth/me", Access.SIGNED_IN),
          new Route(HttpMethod.GET, "/api/v1/servers", Access.ADMIN),
          new Route(HttpMethod.POST, "/api/v1/servers", Access.ADMIN),
          new Route(HttpMethod.PATCH, "/api/v1/servers/{id}", Access.ADMIN),
          new Route(HttpMethod.POST, "/api/v1/servers/{id}/sync", Access.ADMIN),
          new Route(HttpMethod.GET, "/api/v1/tools", Access.ADMIN),
          new Route(HttpMethod.PATCH, "/api/v1/tools/{id}", Access.ADMIN),
          new Route(HttpMethod.GET, "/api/v1/principals", Access.ADMIN),
          new Route(HttpMethod.POST, "/api/v1/principals", Access.ADMIN),
          new Route(HttpMethod.GET, "/api/v1/principals/{id}/keys", Access.ADMIN),
          new Route(HttpMethod.POST, "/api/v1/principals/{id}/keys", Access.ADMIN),
          new Route(HttpMethod.DELETE, "/api/v1/keys/{id}", Access.ADMIN),
          new Route(HttpMethod.GET, "/api/v1/policy", Access.ADMIN),
          new Route(HttpMethod.POST, "/api/v1/policy", Access.ADMIN),
          new Route(HttpMethod.GET, "/api/v1/audit", Access.VIEWER),
          new Route(HttpMethod.GET, "/api/v1/audit/verify", Access.VIEWER),
          new Route(HttpMethod.GET, "/api/v1/audit/export", Access.ADMIN),
          new Route(HttpMethod.GET, "/.well-known/aduana-audit-key", Access.PUBLIC));

  /**
   * Prevents instances: the class only holds the table.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private RouteAccess() {}

  /**
   * Finds the row for a method and a declared pattern.
   *
   * @param method the HTTP method
   * @param pattern the pattern as declared by a controller
   * @return the row, if the table lists it
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static Optional<Route> find(HttpMethod method, String pattern) {
    return ROUTES.stream()
        .filter(route -> route.method().equals(method) && route.pattern().equals(pattern))
        .findFirst();
  }
}
