// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One real PostgreSQL for every integration test, from the same pinned image as the stack.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabase {

  /** The image the stack runs, pinned by digest. */
  public static final String IMAGE =
      "postgres:18.7@sha256:9f4fd4aa918f52ae3d6f3b687f1de90d8f867cd4d520021545a9a50c120eae3b";

  private static final PostgreSQLContainer CONTAINER =
      new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));

  /**
   * Starts the container once per test JVM and hands its connection details to Spring.
   *
   * @return the running container
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Bean
  @ServiceConnection
  public PostgreSQLContainer postgres() {
    if (!CONTAINER.isRunning()) {
      CONTAINER.start();
    }
    return CONTAINER;
  }
}
