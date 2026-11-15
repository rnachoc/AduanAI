// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.policy;

import io.github.rnachoc.aduana.gateway.approval.ApprovalService;
import io.github.rnachoc.aduana.gateway.catalog.CatalogService;
import io.github.rnachoc.aduana.gateway.catalog.CatalogTool;
import io.github.rnachoc.aduana.gateway.events.EventLog;
import io.github.rnachoc.aduana.gateway.identity.Principal;
import io.github.rnachoc.aduana.policy.CallContext;
import io.github.rnachoc.aduana.policy.Decision;
import io.github.rnachoc.aduana.policy.PolicyBundle;
import io.github.rnachoc.aduana.policy.PolicyEvaluator;
import io.github.rnachoc.aduana.policy.PolicyParser;
import io.github.rnachoc.aduana.policy.PolicyTestRunner;
import io.github.rnachoc.aduana.policy.PolicyTypeCheck;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Loads policy bundles (validated, tested and type-checked first) and answers decisions with the
 * active one. A bundle that fails any check never replaces the active bundle.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Service
public class PolicyService {

  private final PolicyRepository repository;
  private final CatalogService catalog;
  private final EventLog events;
  private final ApprovalService approvals;
  private final Clock clock;
  private final Map<UUID, PolicyBundle> active = new ConcurrentHashMap<>();

  /**
   * What happened to a bundle someone tried to load.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public sealed interface LoadResult {

    /**
     * The bundle is now active.
     *
     * @param sha256 its identity
     * @param rules how many rules it has
     * @param tests how many test cases passed
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    record Loaded(String sha256, int rules, int tests) implements LoadResult {}

    /**
     * The bundle was refused; the previous one stays active.
     *
     * @param errors why
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    record Rejected(List<String> errors) implements LoadResult {}
  }

  /**
   * Creates the service.
   *
   * @param repository bundle storage
   * @param catalog tool schemas for the type check
   * @param events the event log
   * @param approvals expires approvals the new bundle makes obsolete
   * @param clock the clock
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public PolicyService(
      PolicyRepository repository,
      CatalogService catalog,
      EventLog events,
      ApprovalService approvals,
      Clock clock) {
    this.repository = repository;
    this.catalog = catalog;
    this.events = events;
    this.approvals = approvals;
    this.clock = clock;
  }

  /**
   * Validates, tests, type-checks and activates a bundle.
   *
   * @param tenantId the tenant
   * @param source the bundle texts
   * @param actorId who loaded it, if a person did
   * @return loaded, or rejected with every reason
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Transactional
  public LoadResult load(UUID tenantId, PolicySource source, Optional<UUID> actorId) {
    PolicyParser.Result parsed = PolicyParser.parse(source.policy(), source.tests());
    if (parsed instanceof PolicyParser.Result.Invalid(List<String> errors)) {
      return reject(tenantId, errors, actorId);
    }
    PolicyBundle bundle = ((PolicyParser.Result.Valid) parsed).bundle();
    List<String> errors = new ArrayList<>(PolicyTypeCheck.check(bundle, fieldTypes(tenantId)));
    for (PolicyTestRunner.Outcome outcome : PolicyTestRunner.run(bundle)) {
      if (!outcome.passed()) {
        errors.add("test " + outcome.file() + " / " + outcome.name() + ": " + outcome.problems());
      }
    }
    if (!errors.isEmpty()) {
      return reject(tenantId, errors, actorId);
    }
    boolean alreadyActive =
        repository.active(tenantId).map(row -> row.sha256().equals(bundle.sha256())).orElse(false);
    if (alreadyActive) {
      active.put(tenantId, bundle);
      return new LoadResult.Loaded(bundle.sha256(), bundle.rules().size(), bundle.tests().size());
    }
    repository.activate(tenantId, bundle.sha256(), source, actorId, clock.instant());
    active.put(tenantId, bundle);
    approvals.supersede(tenantId, bundle.sha256());
    events.record(
        tenantId,
        new EventLog.Event(
            "POLICY_LOADED", actorId, Map.of("policy_bundle_hash", bundle.sha256())));
    return new LoadResult.Loaded(bundle.sha256(), bundle.rules().size(), bundle.tests().size());
  }

  /**
   * Returns the active bundle, reading it from the database the first time.
   *
   * @param tenantId the tenant
   * @return the bundle, if one was ever loaded
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<PolicyBundle> activeBundle(UUID tenantId) {
    PolicyBundle cached = active.get(tenantId);
    if (cached != null) {
      return Optional.of(cached);
    }
    Optional<PolicyBundle> stored =
        repository
            .active(tenantId)
            .map(row -> PolicyParser.parse(row.source().policy(), row.source().tests()))
            .filter(PolicyParser.Result.Valid.class::isInstance)
            .map(result -> ((PolicyParser.Result.Valid) result).bundle());
    stored.ifPresent(bundle -> active.put(tenantId, bundle));
    return stored;
  }

  /**
   * Returns the active bundle's texts and identity.
   *
   * @param tenantId the tenant
   * @return the stored bundle, if any
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<PolicyRepository.Stored> activeSource(UUID tenantId) {
    return repository.active(tenantId);
  }

  /**
   * Decides a call with the active bundle; with no bundle loaded, every call is denied.
   *
   * @param principal the caller
   * @param tool the catalog tool
   * @param arguments the arguments
   * @param context the data-flow marks
   * @return the decision and the bundle hash it came from
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Decided decide(
      Principal principal, CatalogTool tool, JsonNode arguments, CallContext.ContextFacts context) {
    Optional<PolicyBundle> bundle = activeBundle(principal.tenantId());
    if (bundle.isEmpty()) {
      return new Decided(
          new Decision.Deny(Decision.NO_MATCHING_RULE, List.of(), List.of()), "none");
    }
    CallContext call = new CallContext(facts(principal), facts(tool), arguments, context);
    return new Decided(PolicyEvaluator.decide(bundle.get(), call), bundle.get().sha256());
  }

  /**
   * Tells whether a tool should appear in a principal's tool list under the active bundle.
   *
   * @param principal the caller
   * @param tool the catalog tool
   * @return true when some rule could let the call through
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public boolean mayOffer(Principal principal, CatalogTool tool) {
    return activeBundle(principal.tenantId())
        .map(bundle -> PolicyEvaluator.mayOffer(bundle, facts(principal), facts(tool)))
        .orElse(false);
  }

  /**
   * A decision and the bundle that made it.
   *
   * @param decision the decision
   * @param bundleSha256 the active bundle's hash, or `none`
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Decided(Decision decision, String bundleSha256) {}

  /**
   * Records a refused bundle.
   *
   * @param tenantId the tenant
   * @param errors why it was refused
   * @param actorId who tried
   * @return the rejection
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private LoadResult reject(UUID tenantId, List<String> errors, Optional<UUID> actorId) {
    events.record(
        tenantId,
        new EventLog.Event(
            "POLICY_REJECTED", actorId, Map.of("errors", String.valueOf(errors.size()))));
    return new LoadResult.Rejected(List.copyOf(errors));
  }

  /**
   * Collects the top-level property types of every catalog tool's input schema.
   *
   * @param tenantId the tenant
   * @return `server__tool` to field to JSON Schema type
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Map<String, Map<String, String>> fieldTypes(UUID tenantId) {
    Map<String, Map<String, String>> types = new HashMap<>();
    for (CatalogTool tool : catalog.tools(tenantId)) {
      Map<String, String> fields = new HashMap<>();
      tool.definition()
          .path("inputSchema")
          .path("properties")
          .properties()
          .forEach(entry -> {
            if (entry.getValue().path("type").isString()) {
              fields.put(entry.getKey(), entry.getValue().path("type").asString());
            }
          });
      types.put(tool.serverName() + "__" + tool.name(), fields);
    }
    return types;
  }

  /**
   * Describes a principal to the evaluator.
   *
   * @param principal the principal
   * @return its facts
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static CallContext.PrincipalFacts facts(Principal principal) {
    return new CallContext.PrincipalFacts(
        principal.name(), principal.attributes(), new HashSet<>(principal.groups()));
  }

  /**
   * Describes a catalog tool to the evaluator.
   *
   * @param tool the tool
   * @return its facts
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static CallContext.ToolFacts facts(CatalogTool tool) {
    return new CallContext.ToolFacts(
        tool.serverName(),
        tool.name(),
        tool.sideEffect(),
        tool.untrustedSource(),
        tool.sensitivity());
  }
}
