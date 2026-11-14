// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.flyway.autoconfigure.FlywayDataSource;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Two ways into the same database: migrations run as the owner, and everything else runs as the
 * least-privileged role `aduana_app`, which may only insert into and read the audit tables (ADR
 * 11).
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Configuration
public class DatabaseConfiguration {

  /** The role every application connection switches to before its first statement. */
  public static final String APPLICATION_ROLE = "aduana_app";

  /**
   * The application pool: every physical connection runs `SET ROLE aduana_app` once, so no query
   * the gateway sends can update or delete an audit entry.
   *
   * @param properties the configured URL and credentials
   * @param connection connection details from a service connection, which win when present
   * @return the pool
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Bean
  @Primary
  public HikariDataSource dataSource(
      DataSourceProperties properties, ObjectProvider<JdbcConnectionDetails> connection) {
    HikariDataSource pool = pool(properties, connection);
    pool.setPoolName("aduana-app");
    pool.setConnectionInitSql("SET ROLE " + APPLICATION_ROLE);
    return pool;
  }

  /**
   * The migration pool, as the owner; Flyway needs more than one connection, at start-up only.
   *
   * @param properties the configured URL and credentials
   * @param connection connection details from a service connection, which win when present
   * @return the pool Flyway uses
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Bean
  @FlywayDataSource
  public DataSource flywayDataSource(
      DataSourceProperties properties, ObjectProvider<JdbcConnectionDetails> connection) {
    HikariDataSource pool = pool(properties, connection);
    pool.setPoolName("aduana-migrations");
    pool.setMaximumPoolSize(3);
    pool.setMinimumIdle(0);
    return pool;
  }

  /**
   * Builds a pool from the service connection when there is one, else from `spring.datasource`.
   *
   * @param properties the configured URL and credentials
   * @param connection connection details, if a service connection provides them
   * @return an unstarted pool
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static HikariDataSource pool(
      DataSourceProperties properties, ObjectProvider<JdbcConnectionDetails> connection) {
    HikariDataSource pool =
        properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    JdbcConnectionDetails details = connection.getIfAvailable();
    if (details != null) {
      pool.setJdbcUrl(details.getJdbcUrl());
      pool.setUsername(details.getUsername());
      pool.setPassword(details.getPassword());
    }
    return pool;
  }
}
