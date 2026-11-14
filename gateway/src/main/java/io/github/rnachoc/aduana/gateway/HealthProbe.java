// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Probes /healthz for the container HEALTHCHECK, because the runtime image has no curl or shell.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class HealthProbe {

  /**
   * Prevents instances: the class is only an entry point.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private HealthProbe() {}

  /**
   * Exits 0 when the local gateway answers 200 on /healthz within two seconds, 1 otherwise.
   *
   * @param arguments optional base URL; defaults to http://127.0.0.1:8080
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static void main(String[] arguments) {
    String base = arguments.length > 0 ? arguments[0] : "http://127.0.0.1:8080";
    System.exit(isHealthy(URI.create(base + "/healthz")) ? 0 : 1);
  }

  /**
   * Sends one GET and reports whether it returned 200.
   *
   * @param uri the health endpoint
   * @return true only for a 200 response
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static boolean isHealthy(URI uri) {
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build();
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
    } catch (IOException exception) {
      return false;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
