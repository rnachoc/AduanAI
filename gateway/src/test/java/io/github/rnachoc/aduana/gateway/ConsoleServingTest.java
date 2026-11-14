// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.aduana.gateway.security.SecurityHeadersFilter;
import io.github.rnachoc.aduana.gateway.support.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Phase 9: the gateway serves the console's files to anyone, and every response carries a content
 * security policy that allows only its own origin (CA9.2).
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"aduana.master-key=local-only", "aduana.catalog.sync-interval=PT1H"})
@Import(TestDatabase.class)
class ConsoleServingTest {

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @LocalServerPort private int port;

  /**
   * Sends a GET.
   *
   * @param path the path
   * @return the response
   * @throws Exception if the request fails
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private HttpResponse<String> get(String path) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void the_console_is_served_with_a_same_origin_content_security_policy() throws Exception {
    HttpResponse<String> page = get("/");
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body()).contains("<app-root>");
    String policy = page.headers().firstValue("Content-Security-Policy").orElseThrow();
    assertThat(policy).isEqualTo(SecurityHeadersFilter.CONTENT_SECURITY_POLICY);
    assertThat(policy).doesNotContain("unsafe-inline", "unsafe-eval", "http:", "https:", "*");
    assertThat(page.headers().firstValue("X-Frame-Options")).contains("DENY");
  }

  @Test
  void api_responses_carry_the_same_headers() throws Exception {
    HttpResponse<String> me = get("/api/v1/auth/me");
    assertThat(me.statusCode()).isEqualTo(401);
    assertThat(me.headers().firstValue("Content-Security-Policy"))
        .contains(SecurityHeadersFilter.CONTENT_SECURITY_POLICY);
  }

  @Test
  void paths_outside_the_console_files_are_refused() throws Exception {
    assertThat(get("/application.yaml").statusCode()).isIn(401, 403, 404);
    assertThat(get("/db/migration/V1__identity_and_catalog.sql").statusCode()).isIn(401, 403, 404);
  }
}
