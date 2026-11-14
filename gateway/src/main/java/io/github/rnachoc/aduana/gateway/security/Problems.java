// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes RFC 9457 problem documents from places that run before the controllers, such as security
 * filters.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class Problems {

  /**
   * Prevents instances: the class only has a static helper.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Problems() {}

  /**
   * Writes a problem document with a stable code.
   *
   * @param response the response
   * @param status the HTTP status
   * @param code the stable error code, uppercase letters and underscores
   * @throws IOException if the body cannot be written
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static void write(HttpServletResponse response, int status, String code)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    String body =
        "{\"type\":\"about:blank\",\"title\":\""
            + code.replace('_', ' ').toLowerCase(java.util.Locale.ROOT)
            + "\",\"status\":"
            + status
            + ",\"code\":\""
            + code
            + "\"}";
    response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
  }
}
