// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.config;

import io.github.rnachoc.aduana.gateway.catalog.CatalogServer;
import io.github.rnachoc.aduana.gateway.catalog.CatalogService;
import io.github.rnachoc.aduana.gateway.identity.ConsoleUser;
import io.github.rnachoc.aduana.gateway.identity.ConsoleUserService;
import io.github.rnachoc.aduana.gateway.identity.Tenants;
import io.github.rnachoc.aduana.gateway.upstream.UpstreamServer;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Brings a fresh installation to a usable state: the first administrator from the environment, the
 * servers listed in configuration, and a periodic sync of every server's definitions.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class Bootstrap implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(Bootstrap.class);

  private final AduanaProperties properties;
  private final CatalogService catalog;
  private final ConsoleUserService users;
  private final String adminEmail;
  private final String adminPassword;

  /**
   * Creates the bootstrap.
   *
   * @param properties the configured servers
   * @param catalog the catalog
   * @param users creates the first administrator
   * @param adminEmail the first administrator's address, if set
   * @param adminPassword the first administrator's password, if set
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Bootstrap(
      AduanaProperties properties,
      CatalogService catalog,
      ConsoleUserService users,
      @Value("${aduana.bootstrap.admin-email:}") String adminEmail,
      @Value("${aduana.bootstrap.admin-password:}") String adminPassword) {
    this.properties = properties;
    this.catalog = catalog;
    this.users = users;
    this.adminEmail = adminEmail;
    this.adminPassword = adminPassword;
  }

  /**
   * Runs once at start-up.
   *
   * @param arguments ignored
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Override
  public void run(ApplicationArguments arguments) {
    if (!adminEmail.isBlank() && !adminPassword.isBlank() && !users.anyUserExists()) {
      users.create(Tenants.DEFAULT, adminEmail, adminPassword, ConsoleUser.Role.ADMIN);
      LOG.info("created the first administrator {}", adminEmail);
    }
    for (UpstreamServer upstream : properties.upstreams()) {
      if (catalog.serverByName(Tenants.DEFAULT, upstream.name()).isEmpty()) {
        catalog.register(
            Tenants.DEFAULT,
            new CatalogService.ServerRequest(
                upstream.name(),
                upstream.endpoint().toString(),
                Optional.empty(),
                true,
                "active",
                upstream.timeout()),
            Optional.empty());
      }
    }
    syncAll();
  }

  /**
   * Re-reads every active server's tool definitions, so a changed definition is caught even if
   * nobody asks for a sync.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Scheduled(
      fixedDelayString = "${aduana.catalog.sync-interval:PT5M}",
      initialDelayString = "${aduana.catalog.sync-interval:PT5M}")
  public void syncAll() {
    for (CatalogServer server : catalog.servers(Tenants.DEFAULT)) {
      if (server.isActive()) {
        CatalogService.SyncReport report = catalog.sync(Tenants.DEFAULT, server.id());
        LOG.info(
            "synced {}: reachable={} added={} changed={} removed={}",
            server.name(),
            report.reachable(),
            report.added(),
            report.changed(),
            report.removed());
      }
    }
  }
}
