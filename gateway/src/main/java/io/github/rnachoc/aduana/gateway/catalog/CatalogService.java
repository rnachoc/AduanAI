// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.catalog;

import io.github.rnachoc.aduana.gateway.events.EventLog;
import io.github.rnachoc.aduana.gateway.inspection.InjectionScanner;
import io.github.rnachoc.aduana.gateway.upstream.UpstreamClient;
import io.github.rnachoc.aduana.gateway.upstream.UpstreamServer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps the catalog: registers servers, pins their tool definitions, and takes a tool out of
 * service when its server changes the definition until an administrator reviews it.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Service
public class CatalogService {

  /** A tool in service. */
  public static final String ACTIVE = "active";

  /** A tool whose server changed its definition; hidden until reviewed. */
  public static final String CHANGED = "changed_pending_review";

  /** A tool that appeared after the first sync; hidden until reviewed. */
  public static final String NEW = "new_pending_review";

  /** A tool its server no longer lists. */
  public static final String REMOVED = "removed";

  private final CatalogRepository repository;
  private final CredentialCipher cipher;
  private final DefinitionHasher hasher;
  private final UpstreamClient upstreams;
  private final EventLog events;
  private final InjectionScanner scanner;
  private final Clock clock;

  /**
   * What an administrator asks for when registering or changing a server.
   *
   * @param name the server name (registration only)
   * @param endpoint the server URL
   * @param credential a credential to inject, if any; never returned
   * @param strict whether unknown argument fields are refused
   * @param status active or disabled
   * @param timeout the call timeout
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record ServerRequest(
      String name,
      String endpoint,
      Optional<Secret> credential,
      boolean strict,
      String status,
      Duration timeout) {}

  /**
   * What a sync found.
   *
   * @param added tools seen for the first time
   * @param changed tools whose definition changed
   * @param removed tools the server no longer lists
   * @param reachable whether the server answered at all
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record SyncReport(
      List<String> added, List<String> changed, List<String> removed, boolean reachable) {}

  /**
   * An administrator's change to a tool's metadata or review state; empty fields stay as they are.
   *
   * @param sideEffect whether the tool has side effects
   * @param untrustedSource whether its results are untrusted
   * @param sensitivity low, medium or high
   * @param cost budget units per call
   * @param enabled whether the tool may be offered
   * @param acceptDefinition true to accept a pending definition and return the tool to service
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record ToolChange(
      Optional<Boolean> sideEffect,
      Optional<Boolean> untrustedSource,
      Optional<String> sensitivity,
      Optional<Integer> cost,
      Optional<Boolean> enabled,
      boolean acceptDefinition) {}

  /**
   * Creates the service.
   *
   * @param repository catalog storage
   * @param cipher seals credentials
   * @param hasher pins definitions
   * @param upstreams reads tool lists from servers
   * @param events the event log
   * @param scanner marks descriptions that address agents
   * @param clock the clock
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public CatalogService(
      CatalogRepository repository,
      CredentialCipher cipher,
      DefinitionHasher hasher,
      UpstreamClient upstreams,
      EventLog events,
      InjectionScanner scanner,
      Clock clock) {
    this.repository = repository;
    this.cipher = cipher;
    this.hasher = hasher;
    this.upstreams = upstreams;
    this.events = events;
    this.scanner = scanner;
    this.clock = clock;
  }

  /**
   * Registers a server. Its tools are pinned by the first sync.
   *
   * @param tenantId the tenant
   * @param request the server's settings
   * @param actorId who registered it
   * @return the stored server
   * @throws IllegalArgumentException if the name or endpoint is invalid or the name is taken
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Transactional
  public CatalogServer register(UUID tenantId, ServerRequest request, Optional<UUID> actorId) {
    new UpstreamServer(request.name(), URI.create(request.endpoint()), request.timeout());
    if (repository.serverByName(tenantId, request.name()).isPresent()) {
      throw new IllegalArgumentException("A server with that name exists");
    }
    UUID id = UUID.randomUUID();
    CatalogServer server =
        new CatalogServer(
            tenantId,
            id,
            request.name(),
            "http",
            request.endpoint(),
            request.strict(),
            request.status(),
            request.timeout(),
            request.credential().isPresent());
    repository.insertServer(
        server, request.credential().map(c -> cipher.seal(tenantId, id, c)), clock.instant());
    recordServerChange(tenantId, server, "registered", actorId);
    return server;
  }

  /**
   * Changes a server's endpoint, credential, strictness, status or timeout.
   *
   * @param tenantId the tenant
   * @param serverId the server
   * @param request the new settings; the name is ignored
   * @param actorId who changed it
   * @return the stored server
   * @throws IllegalArgumentException if the server does not exist or the endpoint is invalid
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Transactional
  public CatalogServer update(
      UUID tenantId, UUID serverId, ServerRequest request, Optional<UUID> actorId) {
    CatalogServer current = server(tenantId, serverId);
    new UpstreamServer(current.name(), URI.create(request.endpoint()), request.timeout());
    CatalogServer updated =
        new CatalogServer(
            tenantId,
            serverId,
            current.name(),
            current.transport(),
            request.endpoint(),
            request.strict(),
            request.status(),
            request.timeout(),
            current.hasCredential() || request.credential().isPresent());
    repository.updateServer(
        updated, request.credential().map(c -> cipher.seal(tenantId, serverId, c)));
    recordServerChange(tenantId, updated, "updated", actorId);
    return updated;
  }

  /**
   * Reads the server's tool list and reconciles it with the pinned definitions.
   *
   * <p>On a server's first sync every tool is accepted. Afterwards a changed definition takes its
   * tool out of service ({@value #CHANGED}) and a new tool waits for review ({@value #NEW}).
   *
   * @param tenantId the tenant
   * @param serverId the server
   * @return what changed
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Transactional
  public SyncReport sync(UUID tenantId, UUID serverId) {
    CatalogServer server = server(tenantId, serverId);
    Optional<List<JsonNode>> published = upstreams.listTools(server.upstream(), credential(server));
    if (published.isEmpty()) {
      return new SyncReport(List.of(), List.of(), List.of(), false);
    }
    Map<String, CatalogTool> pinned =
        repository.tools(tenantId, Optional.of(serverId)).stream()
            .collect(Collectors.toMap(CatalogTool::name, Function.identity()));
    boolean firstSync = pinned.isEmpty();
    SyncReport report =
        new SyncReport(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), true);
    Set<String> seen = new HashSet<>();
    for (JsonNode tool : published.get()) {
      String name = tool.path("name").asString();
      if (name.isEmpty() || !seen.add(name)) {
        continue;
      }
      reconcile(server, firstSync, tool, Optional.ofNullable(pinned.get(name)), report);
      // Descriptions are third-party text: mark the ones that address agents (plan §5.3).
      repository.setDescriptionFlags(
          tenantId, serverId, name, scanner.scan(hasher.definition(tool)));
    }
    for (CatalogTool tool : pinned.values()) {
      if (!seen.contains(tool.name()) && !REMOVED.equals(tool.state())) {
        repository.setState(tenantId, tool.id(), REMOVED, clock.instant());
        report.removed().add(tool.name());
      }
    }
    return report;
  }

  /**
   * Reconciles one published tool with its pinned row.
   *
   * @param server the server
   * @param firstSync whether the server had no tools pinned yet
   * @param published the tool as the server lists it now
   * @param pinned the stored tool, if any
   * @param report collects what changed
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private void reconcile(
      CatalogServer server,
      boolean firstSync,
      JsonNode published,
      Optional<CatalogTool> pinned,
      SyncReport report) {
    ObjectNode definition = hasher.definition(published);
    String sha = hasher.sha256(definition);
    String name = published.path("name").asString();
    if (pinned.isEmpty()) {
      repository.insertTool(
          new CatalogTool(
              server.tenantId(),
              UUID.randomUUID(),
              server.id(),
              server.name(),
              name,
              definition,
              sha,
              Optional.empty(),
              false,
              false,
              "low",
              0,
              true,
              firstSync ? ACTIVE : NEW,
              List.of()),
          clock.instant());
      report.added().add(name);
      return;
    }
    CatalogTool tool = pinned.get();
    boolean sameAsPinned = sha.equals(tool.definitionSha256());
    if (sameAsPinned && REMOVED.equals(tool.state())) {
      repository.setState(server.tenantId(), tool.id(), NEW, clock.instant());
      report.added().add(name);
    } else if (!sameAsPinned
        && !tool.pendingDefinition().map(hasher::sha256).orElse("").equals(sha)) {
      repository.markPending(
          server.tenantId(), tool.id(), definition, sha, CHANGED, clock.instant());
      events.record(
          server.tenantId(),
          new EventLog.Event(
              "TOOL_DEFINITION_CHANGED",
              Optional.empty(),
              Map.of("tool", server.name() + "__" + name, "definition_sha256", sha)));
      report.changed().add(name);
    }
  }

  /**
   * Applies an administrator's change to a tool.
   *
   * @param tenantId the tenant
   * @param toolId the tool
   * @param change the change
   * @param actorId who made it
   * @return the tool after the change
   * @throws IllegalArgumentException if the tool does not exist or a value is invalid
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Transactional
  public CatalogTool changeTool(
      UUID tenantId, UUID toolId, ToolChange change, Optional<UUID> actorId) {
    CatalogTool tool = tool(tenantId, toolId);
    String sensitivity = change.sensitivity().orElse(tool.sensitivity());
    if (!Set.of("low", "medium", "high").contains(sensitivity)) {
      throw new IllegalArgumentException("Sensitivity is low, medium or high");
    }
    int cost = change.cost().orElse(tool.cost());
    if (cost < 0) {
      throw new IllegalArgumentException("Cost cannot be negative");
    }
    CatalogTool updated =
        new CatalogTool(
            tenantId,
            toolId,
            tool.serverId(),
            tool.serverName(),
            tool.name(),
            tool.definition(),
            tool.definitionSha256(),
            tool.pendingDefinition(),
            change.sideEffect().orElse(tool.sideEffect()),
            change.untrustedSource().orElse(tool.untrustedSource()),
            sensitivity,
            cost,
            change.enabled().orElse(tool.enabled()),
            tool.state(),
            tool.descriptionFlags());
    repository.updateMetadata(updated, clock.instant());
    if (change.acceptDefinition() && (CHANGED.equals(tool.state()) || NEW.equals(tool.state()))) {
      repository.acceptPending(tenantId, toolId, clock.instant());
    }
    Map<String, String> detail = new HashMap<>();
    detail.put("tool", tool.serverName() + "__" + tool.name());
    detail.put("accepted_definition", String.valueOf(change.acceptDefinition()));
    events.record(tenantId, new EventLog.Event("TOOL_CHANGED", actorId, detail));
    return tool(tenantId, toolId);
  }

  /**
   * Finds a tool agents may call: enabled, active, on an active server.
   *
   * @param tenantId the tenant
   * @param serverName the server name
   * @param toolName the tool name
   * @return the tool and its server, or empty in every other case, without saying which
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<OfferedTool> offeredTool(UUID tenantId, String serverName, String toolName) {
    Optional<CatalogTool> tool = repository.tool(tenantId, serverName, toolName);
    Optional<CatalogServer> server = repository.serverByName(tenantId, serverName);
    if (tool.isEmpty() || server.isEmpty() || !tool.get().isOffered() || !server.get().isActive()) {
      return Optional.empty();
    }
    return Optional.of(new OfferedTool(tool.get(), server.get(), credential(server.get())));
  }

  /**
   * Lists the tools agents may see, on active servers.
   *
   * @param tenantId the tenant
   * @return offered tools by server and name
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<CatalogTool> offeredTools(UUID tenantId) {
    Set<UUID> activeServers =
        repository.servers(tenantId).stream()
            .filter(CatalogServer::isActive)
            .map(CatalogServer::id)
            .collect(Collectors.toSet());
    return repository.tools(tenantId, Optional.empty()).stream()
        .filter(CatalogTool::isOffered)
        .filter(tool -> activeServers.contains(tool.serverId()))
        .toList();
  }

  /**
   * Lists every server.
   *
   * @param tenantId the tenant
   * @return the servers
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<CatalogServer> servers(UUID tenantId) {
    return repository.servers(tenantId);
  }

  /**
   * Finds a server by name.
   *
   * @param tenantId the tenant
   * @param name the name
   * @return the server
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<CatalogServer> serverByName(UUID tenantId, String name) {
    return repository.serverByName(tenantId, name);
  }

  /**
   * Lists every tool in any state.
   *
   * @param tenantId the tenant
   * @return the tools
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<CatalogTool> tools(UUID tenantId) {
    return repository.tools(tenantId, Optional.empty());
  }

  /**
   * A tool that may be called, with what is needed to call it.
   *
   * @param tool the tool
   * @param server its server
   * @param credential the credential to inject, if any
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record OfferedTool(CatalogTool tool, CatalogServer server, Optional<Secret> credential) {}

  /**
   * Loads a server or fails.
   *
   * @param tenantId the tenant
   * @param serverId the server
   * @return the server
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private CatalogServer server(UUID tenantId, UUID serverId) {
    return repository
        .server(tenantId, serverId)
        .orElseThrow(() -> new IllegalArgumentException("No such server"));
  }

  /**
   * Loads a tool or fails.
   *
   * @param tenantId the tenant
   * @param toolId the tool
   * @return the tool
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private CatalogTool tool(UUID tenantId, UUID toolId) {
    return repository
        .tool(tenantId, toolId)
        .orElseThrow(() -> new IllegalArgumentException("No such tool"));
  }

  /**
   * Decrypts a server's credential, if it has one.
   *
   * @param server the server
   * @return the credential
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Optional<Secret> credential(CatalogServer server) {
    if (!server.hasCredential()) {
      return Optional.empty();
    }
    return repository
        .credential(server.tenantId(), server.id())
        .map(sealed -> cipher.open(server.tenantId(), server.id(), sealed));
  }

  /**
   * Records a server change without its credential.
   *
   * @param tenantId the tenant
   * @param server the server
   * @param action what happened
   * @param actorId who did it
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private void recordServerChange(
      UUID tenantId, CatalogServer server, String action, Optional<UUID> actorId) {
    events.record(
        tenantId,
        new EventLog.Event(
            "SERVER_CHANGED",
            actorId,
            Map.of("server", server.name(), "action", action, "status", server.status())));
  }
}
