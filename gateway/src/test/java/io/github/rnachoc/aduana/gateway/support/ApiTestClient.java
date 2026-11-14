// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.support;

import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Talks to the administration API the way the console does: session cookie plus the CSRF token
 * echoed from its cookie into a header.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class ApiTestClient {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final URI base;
  private final CookieManager cookies = new CookieManager();
  private final HttpClient http;
  private boolean sendCsrf = true;

  /**
   * The status and parsed body of one call.
   *
   * @param status the HTTP status
   * @param body the parsed body, or an empty object
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Reply(int status, JsonNode body) {}

  /**
   * Targets a gateway.
   *
   * @param base the gateway's base URL
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ApiTestClient(URI base) {
    this.base = base;
    this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
  }

  /**
   * Stops sending the CSRF header, to prove the API demands it.
   *
   * @return this client
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ApiTestClient withoutCsrf() {
    sendCsrf = false;
    return this;
  }

  /**
   * Signs in and fetches the CSRF cookie.
   *
   * @param email the address
   * @param password the password
   * @return the login reply
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Reply login(String email, String password) {
    Reply reply =
        send(
            "POST",
            "/api/v1/auth/login",
            "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    if (reply.status() == 200) {
      send("GET", "/api/v1/auth/me", null);
    }
    return reply;
  }

  /**
   * Sends a request and returns the body as text, for answers that are not one JSON document.
   *
   * @param method the HTTP method
   * @param path the path under the base URL
   * @return the body
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public String sendRaw(String method, String path) {
    HttpRequest request =
        HttpRequest.newBuilder(base.resolve(path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString()).body();
    } catch (IOException exception) {
      throw new IllegalStateException("API request failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", exception);
    }
  }

  /**
   * Sends a request with an optional JSON body.
   *
   * @param method the HTTP method
   * @param path the path under the base URL
   * @param body the JSON body, or null for none
   * @return the reply
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Reply send(String method, String path, String body) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(base.resolve(path))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (sendCsrf) {
      cookies.getCookieStore().getCookies().stream()
          .filter(cookie -> "XSRF-TOKEN".equals(cookie.getName()))
          .map(HttpCookie::getValue)
          .findFirst()
          .ifPresent(token -> builder.header("X-XSRF-TOKEN", token));
    }
    try {
      HttpResponse<String> response =
          http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      String text = response.body();
      return new Reply(
          response.statusCode(), text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text));
    } catch (IOException exception) {
      throw new IllegalStateException("API request failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", exception);
    }
  }
}
