// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.security;

import io.github.rnachoc.aduana.gateway.events.EventLog;
import io.github.rnachoc.aduana.gateway.identity.Principal;
import io.github.rnachoc.aduana.gateway.identity.PrincipalService;
import io.github.rnachoc.aduana.gateway.identity.Tenants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates every request to the MCP endpoint with an Aduana API key (`adn_…`) or, when OIDC is
 * configured, a JWT; anything else gets 401 and an `AUTH_FAILED` event.
 *
 * <p>Only agents pass: a console user's identity cannot call tools.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public class AgentAuthenticationFilter extends OncePerRequestFilter {

  /** The request attribute that carries the authenticated {@link Principal}. */
  public static final String PRINCIPAL = "aduana.principal";

  private static final String BEARER = "Bearer ";

  private final PrincipalService principals;
  private final ObjectProvider<JwtDecoder> jwtDecoder;
  private final EventLog events;

  /**
   * Creates the filter.
   *
   * @param principals authenticates API keys and finds principals
   * @param jwtDecoder validates JWTs when OIDC is configured
   * @param events records failed attempts
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public AgentAuthenticationFilter(
      PrincipalService principals, ObjectProvider<JwtDecoder> jwtDecoder, EventLog events) {
    this.principals = principals;
    this.jwtDecoder = jwtDecoder;
    this.events = events;
  }

  /**
   * Resolves the agent or answers 401.
   *
   * @param request the request
   * @param response the response
   * @param chain the rest of the chain
   * @throws ServletException if a later filter fails
   * @throws IOException if writing the response fails
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!"POST".equals(request.getMethod())) {
      chain.doFilter(request, response);
      return;
    }
    Optional<Principal> principal =
        Optional.ofNullable(request.getHeader("Authorization"))
            .filter(header -> header.startsWith(BEARER))
            .map(header -> header.substring(BEARER.length()).trim())
            .flatMap(this::resolve)
            .filter(found -> found.kind() == Principal.Kind.AGENT && found.isActive());
    if (principal.isEmpty()) {
      events.record(
          Tenants.DEFAULT,
          new EventLog.Event(
              "AUTH_FAILED",
              Optional.empty(),
              Map.of("path", request.getRequestURI(), "remote", request.getRemoteAddr())));
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setHeader("WWW-Authenticate", "Bearer");
      response.setContentType("application/json");
      response
          .getOutputStream()
          .write(
              "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32001,\"message\":\"Unauthorized\"}}"
                  .getBytes(StandardCharsets.UTF_8));
      return;
    }
    request.setAttribute(PRINCIPAL, principal.get());
    chain.doFilter(request, response);
  }

  /**
   * Turns a bearer value into a principal: API key first, then JWT if OIDC is on.
   *
   * @param token the bearer value
   * @return the principal, if the token is valid
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Optional<Principal> resolve(String token) {
    Optional<Principal> byKey = principals.authenticate(token);
    if (byKey.isPresent()) {
      return byKey;
    }
    JwtDecoder decoder = jwtDecoder.getIfAvailable();
    if (decoder == null) {
      return Optional.empty();
    }
    try {
      String subject = decoder.decode(token).getSubject();
      return subject == null ? Optional.empty() : principals.findByName(Tenants.DEFAULT, subject);
    } catch (JwtException exception) {
      return Optional.empty();
    }
  }
}
