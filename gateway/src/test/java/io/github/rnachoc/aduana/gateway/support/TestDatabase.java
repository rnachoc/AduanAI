// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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

  /**
   * Every cached Spring test context keeps its own pools open, so the default of 100 connections
   * runs out once there are a dozen contexts.
   */
  private static final PostgreSQLContainer CONTAINER =
      new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
          .withCommand("postgres", "-c", "max_connections=400");

  /**
   * Starts the container once per test JVM and hands its connection details to Spring.
   *
   * @return the running container
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Bean
  @ServiceConnection
  public PostgreSQLContainer postgres() {
    return container();
  }

  /**
   * Returns the shared container, started on first use.
   *
   * @return the running container
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static PostgreSQLContainer container() {
    if (!CONTAINER.isRunning()) {
      CONTAINER.start();
    }
    return CONTAINER;
  }

  /**
   * Opens a connection as the database owner, bypassing the application role.
   *
   * @return a new connection; the caller closes it
   * @throws SQLException if the connection fails
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static Connection ownerConnection() throws SQLException {
    PostgreSQLContainer postgres = container();
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }
}
