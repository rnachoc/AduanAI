// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.web.api;

import io.github.rnachoc.aduana.gateway.catalog.CatalogServer;
import io.github.rnachoc.aduana.gateway.catalog.CatalogService;
import io.github.rnachoc.aduana.gateway.catalog.CatalogTool;
import io.github.rnachoc.aduana.gateway.catalog.Secret;
import io.github.rnachoc.aduana.gateway.identity.ConsoleUser;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Administers the catalog: servers, their sync, and tool metadata and reviews.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

  private final CatalogService catalog;

  /**
   * A server registration or change. The credential is write-only.
   *
   * @param name the server name (registration only)
   * @param endpoint the URL
   * @param credential a bearer credential to inject, if any
   * @param strict whether unknown argument fields are refused; true when absent
   * @param status active or disabled; active when absent
   * @param timeoutMs the call timeout; 10 000 when absent
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record ServerBody(
      String name,
      String endpoint,
      String credential,
      Boolean strict,
      String status,
      Integer timeoutMs) {}

  /**
   * A server as the API shows it: never the credential, only whether there is one.
   *
   * @param id the id
   * @param name the name
   * @param transport http or stdio
   * @param endpoint the URL
   * @param strict whether unknown fields are refused
   * @param status active or disabled
   * @param timeoutMs the call timeout
   * @param hasCredential whether a credential is stored
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record ServerView(
      UUID id,
      String name,
      String transport,
      String endpoint,
      boolean strict,
      String status,
      long timeoutMs,
      boolean hasCredential) {}

  /**
   * A tool as the API shows it.
   *
   * @param id the id
   * @param server the server name
   * @param name the tool name
   * @param definition the pinned definition
   * @param definitionSha256 its hash
   * @param pendingDefinition a changed definition waiting for review
   * @param sideEffect whether it has side effects
   * @param untrustedSource whether its results are untrusted
   * @param sensitivity low, medium or high
   * @param cost budget units per call
   * @param enabled whether it may be offered
   * @param state its review state
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record ToolView(
      UUID id,
      String server,
      String name,
      JsonNode definition,
      String definitionSha256,
      JsonNode pendingDefinition,
      boolean sideEffect,
      boolean untrustedSource,
      String sensitivity,
      int cost,
      boolean enabled,
      String state) {}

  /**
   * A change to a tool; absent fields stay as they are.
   *
   * @param sideEffect whether it has side effects
   * @param untrustedSource whether its results are untrusted
   * @param sensitivity low, medium or high
   * @param cost budget units per call
   * @param enabled whether it may be offered
   * @param acceptDefinition true to accept the pending definition
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record ToolBody(
      Boolean sideEffect,
      Boolean untrustedSource,
      String sensitivity,
      Integer cost,
      Boolean enabled,
      Boolean acceptDefinition) {}

  /**
   * Creates the controller.
   *
   * @param catalog the catalog service
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public CatalogController(CatalogService catalog) {
    this.catalog = catalog;
  }

  /**
   * Lists servers.
   *
   * @param user the signed-in administrator
   * @return the servers
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @GetMapping("/servers")
  public List<ServerView> servers(@AuthenticationPrincipal ConsoleUser user) {
    return catalog.servers(user.tenantId()).stream().map(CatalogController::view).toList();
  }

  /**
   * Registers a server and runs its first sync.
   *
   * @param user the signed-in administrator
   * @param body the server
   * @return the stored server
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @PostMapping("/servers")
  @ResponseStatus(HttpStatus.CREATED)
  public ServerView register(
      @AuthenticationPrincipal ConsoleUser user, @RequestBody ServerBody body) {
    CatalogServer server =
        catalog.register(user.tenantId(), request(body), Optional.of(user.principalId()));
    catalog.sync(user.tenantId(), server.id());
    return view(server);
  }

  /**
   * Changes a server.
   *
   * @param user the signed-in administrator
   * @param id the server
   * @param body the new settings
   * @return the stored server
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @PatchMapping("/servers/{id}")
  public ServerView update(
      @AuthenticationPrincipal ConsoleUser user,
      @PathVariable UUID id,
      @RequestBody ServerBody body) {
    return view(
        catalog.update(user.tenantId(), id, request(body), Optional.of(user.principalId())));
  }

  /**
   * Re-reads a server's tool definitions and flags changes.
   *
   * @param user the signed-in administrator
   * @param id the server
   * @return what changed
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @PostMapping("/servers/{id}/sync")
  public CatalogService.SyncReport sync(
      @AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id) {
    return catalog.sync(user.tenantId(), id);
  }

  /**
   * Lists every tool in any state.
   *
   * @param user the signed-in administrator
   * @return the tools
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @GetMapping("/tools")
  public List<ToolView> tools(@AuthenticationPrincipal ConsoleUser user) {
    return catalog.tools(user.tenantId()).stream().map(CatalogController::view).toList();
  }

  /**
   * Changes a tool's metadata or accepts its pending definition.
   *
   * @param user the signed-in administrator
   * @param id the tool
   * @param body the change
   * @return the tool after the change
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @PatchMapping("/tools/{id}")
  public ToolView changeTool(
      @AuthenticationPrincipal ConsoleUser user,
      @PathVariable UUID id,
      @RequestBody ToolBody body) {
    CatalogService.ToolChange change =
        new CatalogService.ToolChange(
            Optional.ofNullable(body.sideEffect()),
            Optional.ofNullable(body.untrustedSource()),
            Optional.ofNullable(body.sensitivity()),
            Optional.ofNullable(body.cost()),
            Optional.ofNullable(body.enabled()),
            Boolean.TRUE.equals(body.acceptDefinition()));
    return view(catalog.changeTool(user.tenantId(), id, change, Optional.of(user.principalId())));
  }

  /**
   * Turns a request body into a service request with defaults.
   *
   * @param body the body
   * @return the request
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static CatalogService.ServerRequest request(ServerBody body) {
    if (body.endpoint() == null) {
      throw new IllegalArgumentException("endpoint is required");
    }
    return new CatalogService.ServerRequest(
        Optional.ofNullable(body.name()).orElse(""),
        body.endpoint(),
        Optional.ofNullable(body.credential()).filter(c -> !c.isEmpty()).map(Secret::new),
        !Boolean.FALSE.equals(body.strict()),
        Optional.ofNullable(body.status()).orElse("active"),
        Duration.ofMillis(Optional.ofNullable(body.timeoutMs()).orElse(10_000)));
  }

  /**
   * Shows a server without its credential.
   *
   * @param server the server
   * @return the view
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static ServerView view(CatalogServer server) {
    return new ServerView(
        server.id(),
        server.name(),
        server.transport(),
        server.endpoint(),
        server.strict(),
        server.status(),
        server.timeout().toMillis(),
        server.hasCredential());
  }

  /**
   * Shows a tool.
   *
   * @param tool the tool
   * @return the view
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static ToolView view(CatalogTool tool) {
    return new ToolView(
        tool.id(),
        tool.serverName(),
        tool.name(),
        tool.definition(),
        tool.definitionSha256(),
        tool.pendingDefinition().orElse(null),
        tool.sideEffect(),
        tool.untrustedSource(),
        tool.sensitivity(),
        tool.cost(),
        tool.enabled(),
        tool.state());
  }
}
