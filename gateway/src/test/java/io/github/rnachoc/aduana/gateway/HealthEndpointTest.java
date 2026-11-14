// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.aduana.gateway.support.TestDatabase;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Starts the whole gateway on a random port and probes it the way the container does.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aduana.master-key=local-only",
      "aduana.catalog.sync-interval=PT1H",
      "aduana.policy.directory=src/test/resources/policies/allow-all"
    })
@Import(TestDatabase.class)
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
