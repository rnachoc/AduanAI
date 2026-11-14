// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Starts the whole gateway on a random port and probes it the way the container does.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {

  @LocalServerPort private int port;

  @Test
  void the_health_probe_reports_a_running_gateway() {
    assertThat(HealthProbe.isHealthy(URI.create("http://127.0.0.1:" + port + "/healthz"))).isTrue();
  }

  @Test
  void the_health_probe_reports_a_missing_gateway() {
    assertThat(HealthProbe.isHealthy(URI.create("http://127.0.0.1:1/healthz"))).isFalse();
  }
}
