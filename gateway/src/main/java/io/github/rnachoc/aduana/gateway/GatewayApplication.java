// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway;

import java.security.Security;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Starts the gateway that sits between AI agents and their MCP servers.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@SpringBootApplication
public class GatewayApplication {

  /**
   * Boots the Spring application context.
   *
   * @param arguments command-line arguments passed to Spring
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static void main(String[] arguments) {
    // Upstreams are containers whose addresses change when they restart; the JVM would otherwise
    // keep a resolved address for 30 s and refuse to reach a restarted server.
    Security.setProperty("networkaddress.cache.ttl", "5");
    Security.setProperty("networkaddress.cache.negative.ttl", "1");
    SpringApplication.run(GatewayApplication.class, arguments);
  }
}
