// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds the headers that keep the console to its own files (plan Phase 9, CA9.2): a content security
 * policy that allows only this origin, no framing, no MIME sniffing and no referrer.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

  /** Only this origin, for every kind of resource; no inline script or style, no plugins. */
  public static final String CONTENT_SECURITY_POLICY =
      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; "
          + "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; "
          + "frame-ancestors 'none'";

  /**
   * Sets the headers and continues.
   *
   * @param request the request
   * @param response the response
   * @param chain the rest of the chain
   * @throws ServletException from the chain
   * @throws IOException from the chain
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("X-Frame-Options", "DENY");
    chain.doFilter(request, response);
  }
}
