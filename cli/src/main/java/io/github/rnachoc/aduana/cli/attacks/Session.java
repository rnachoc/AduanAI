// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.cli.attacks;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * An HTTP client for the gateway: anonymous, signed in as a console user (cookie and CSRF header),
 * or carrying an agent's bearer key.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
final class Session {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final URI base;
  private final CookieManager cookies = new CookieManager();
  private final HttpClient http;
  private final String bearer;

  /**
   * A response.
   *
   * @param status the HTTP status
   * @param body the body
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  record Response(int status, String body) {}

  /**
   * Creates a client.
   *
   * @param base the gateway
   * @param bearer an agent key to send, or null
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  Session(URI base, String bearer) {
    this.base = base;
    this.bearer = bearer;
    this.http =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
  }

  /**
   * Signs in a console user and loads the CSRF cookie.
   *
   * @param email the user
   * @param password the password
   * @return this session
   * @throws IOException if the sign-in is refused
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  Session login(String email, String password) throws IOException {
    Response login =
        send(
            "POST",
            "/api/v1/auth/login",
            JSON.createObjectNode().put("email", email).put("password", password).toString(),
            Map.of());
    if (login.status() != 200) {
      throw new IOException("sign-in of " + email + " refused: HTTP " + login.status());
    }
    send("GET", "/api/v1/auth/me", null, Map.of());
    return this;
  }

  /**
   * Sends a request with the CSRF header when the session has the cookie.
   *
   * @param method the method
   * @param path the path and query
   * @param body a JSON body, or null
   * @param headers extra headers
   * @return the response
   * @throws IOException if the gateway cannot be reached
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  Response send(String method, String path, String body, Map<String, String> headers)
      throws IOException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(30))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }
    for (HttpCookie cookie : cookies.getCookieStore().get(base)) {
      if (cookie.getName().equals("XSRF-TOKEN") && !headers.containsKey("X-No-Csrf")) {
        request.header("X-XSRF-TOKEN", cookie.getValue());
      }
    }
    headers.forEach((name, value) -> {
      if (!name.equals("X-No-Csrf")) {
        request.header(name, value);
      }
    });
    try {
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.body());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", exception);
    }
  }
}
