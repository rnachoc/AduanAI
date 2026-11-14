// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers liveness probes from Docker, compose and the smoke test.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@RestController
public class HealthController {

  /**
   * Reports that the process is up and serving HTTP.
   *
   * @return a constant body, so the probe never depends on downstream systems
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @GetMapping("/healthz")
  public Map<String, String> health() {
    return Map.of("status", "ok");
  }
}
