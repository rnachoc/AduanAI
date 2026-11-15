// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.cli;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A small client for the gateway's administration API, signed in with an e-mail and password.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
final class GatewayApi {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final URI base;
  private final HttpClient http;
  private final CookieManager cookies = new CookieManager();

  /**
   * Creates a client with its own cookie jar.
   *
   * @param base the gateway's base URL
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  GatewayApi(URI base) {
    this.base = base;
    this.http =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
  }

  /**
   * Signs in.
   *
   * @param email the console user's address
   * @param password the password
   * @throws IOException if the gateway refuses or cannot be reached
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  void login(String email, String password) throws IOException {
    ObjectNode body = JSON.createObjectNode().put("email", email).put("password", password);
    HttpRequest request =
        HttpRequest.newBuilder(base.resolve("/api/v1/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
    HttpResponse<String> response = send(request);
    if (response.statusCode() != 200) {
      throw new IOException("sign-in refused: HTTP " + response.statusCode());
    }
  }

  /**
   * Fetches a path as text.
   *
   * @param path the path, without query
   * @param query query parameters as alternating names and values; null values are skipped
   * @return the body
   * @throws IOException if the gateway answers with an error or cannot be reached
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  String get(String path, String... query) throws IOException {
    StringBuilder target = new StringBuilder(path);
    for (int index = 0; index + 1 < query.length; index += 2) {
      if (query[index + 1] != null) {
        target
            .append(target.indexOf("?") < 0 ? '?' : '&')
            .append(query[index])
            .append('=')
            .append(URLEncoder.encode(query[index + 1], StandardCharsets.UTF_8));
      }
    }
    HttpResponse<String> response =
        send(HttpRequest.newBuilder(base.resolve(target.toString())).GET().build());
    if (response.statusCode() != 200) {
      throw new IOException("GET " + path + " answered HTTP " + response.statusCode());
    }
    return response.body();
  }

  /**
   * Posts JSON with the session's CSRF token.
   *
   * @param path the API path
   * @param body the JSON body
   * @return the status and body
   * @throws IOException if the exchange fails
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  HttpResponse<String> post(String path, String body) throws IOException {
    get("/api/v1/auth/me");
    HttpRequest.Builder request =
        HttpRequest.newBuilder(base.resolve(path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    for (HttpCookie cookie : cookies.getCookieStore().get(base)) {
      if (cookie.getName().equals("XSRF-TOKEN")) {
        request.header("X-XSRF-TOKEN", cookie.getValue());
      }
    }
    return send(request.build());
  }

  /**
   * Sends a request, turning an interrupt into an I/O failure with the flag kept.
   *
   * @param request the request
   * @return the response
   * @throws IOException if the exchange fails
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private HttpResponse<String> send(HttpRequest request) throws IOException {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", exception);
    }
  }
}
