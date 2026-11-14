// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway;

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
    SpringApplication.run(GatewayApplication.class, arguments);
  }
}
