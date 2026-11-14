// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.approval;

import io.github.rnachoc.aduana.audit.HashChain;
import io.github.rnachoc.aduana.gateway.audit.AuditLog;
import io.github.rnachoc.aduana.gateway.audit.AuditRecord;
import io.github.rnachoc.aduana.gateway.catalog.CatalogTool;
import io.github.rnachoc.aduana.gateway.identity.ConsoleUser;
import io.github.rnachoc.aduana.gateway.identity.Principal;
import io.github.rnachoc.aduana.gateway.identity.PrincipalRepository;
import io.github.rnachoc.aduana.policy.Decision;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Two-phase approval (plan §5.4): an approval binds to the exact call, is used once, and expires.
 *
 * <p>The binding hash covers tenant, principal, tool, arguments hash and policy bundle hash, so
 * changing a single character of the arguments, or loading a new policy, needs a new approval.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Service
public class ApprovalService {

  private static final Duration RECHECK = Duration.ofMillis(500);

  private final ApprovalRepository repository;
  private final PrincipalRepository principals;
  private final AuditLog audit;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final Duration holdTimeout;
  private final Map<UUID, CompletableFuture<Void>> waiters = new ConcurrentHashMap<>();

  /**
   * Where a request for approval stands.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public sealed interface Outcome {

    /**
     * Returns the approval id.
     *
     * @return the id
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    UUID id();

    /**
     * Approved and not used yet.
     *
     * @param id the approval
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    record Approved(UUID id) implements Outcome {}

    /**
     * Still waiting for a person.
     *
     * @param id the approval
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    record Pending(UUID id) implements Outcome {}

    /**
     * Rejected by a person.
     *
     * @param id the approval
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    record Rejected(UUID id) implements Outcome {}
  }

  /**
   * The call an approval is asked for.
   *
   * @param principal the agent
   * @param tool the catalog tool
   * @param qualifiedTool the agent-facing name
   * @param argsHash hash of the original arguments
   * @param argsRedacted the arguments as the approver sees them
   * @param bundleHash the active bundle
   * @param decision the policy's approval decision
   * @param flags marks to show the approver
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Request(
      Principal principal,
      CatalogTool tool,
      String qualifiedTool,
      String argsHash,
      JsonNode argsRedacted,
      String bundleHash,
      Decision.RequireApproval decision,
      List<String> flags) {}

  /**
   * Thrown when a console user may not decide an approval.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static final class NotAnApprover extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message why
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public NotAnApprover(String message) {
      super(message);
    }
  }

  /**
   * Creates the service.
   *
   * @param repository approval storage
   * @param principals reads approvers' groups
   * @param audit the chained log
   * @param transactions the transaction manager
   * @param clock the clock
   * @param holdTimeout how long a call waits for a decision before answering pending
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ApprovalService(
      ApprovalRepository repository,
      PrincipalRepository principals,
      AuditLog audit,
      PlatformTransactionManager transactions,
      Clock clock,
      @Value("${aduana.approvals.hold-timeout:PT60S}") Duration holdTimeout) {
    this.repository = repository;
    this.principals = principals;
    this.audit = audit;
    this.transactions = new TransactionTemplate(transactions);
    this.clock = clock;
    this.holdTimeout = holdTimeout;
  }

  /**
   * Computes the binding hash of a call.
   *
   * @param tenantId the tenant
   * @param principalId the agent
   * @param qualifiedTool the tool
   * @param argsHash the arguments hash
   * @param bundleHash the bundle hash
   * @return hex SHA-256 of the fields joined with `|`
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static String binding(
      UUID tenantId, UUID principalId, String qualifiedTool, String argsHash, String bundleHash) {
    String joined =
        String.join(
            "|", tenantId.toString(), principalId.toString(), qualifiedTool, argsHash, bundleHash);
    return HashChain.sha256Hex(joined.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Reuses the approval for this exact call if it is still usable, or asks for a new one; then
   * waits up to the hold timeout for a person to decide.
   *
   * @param request the call
   * @return approved, rejected, or still pending after the hold
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Outcome obtain(Request request) {
    UUID tenantId = request.principal().tenantId();
    String binding =
        binding(
            tenantId,
            request.principal().id(),
            request.qualifiedTool(),
            request.argsHash(),
            request.bundleHash());
    Optional<Approval> existing =
        repository
            .latest(tenantId, binding)
            .filter(approval -> approval.expiresAt().isAfter(clock.instant()))
            .filter(approval -> !Approval.CONSUMED.equals(approval.state()))
            .filter(approval -> !Approval.EXPIRED.equals(approval.state()));
    Outcome outcome =
        existing
            .map(ApprovalService::outcomeOf)
            .orElseGet(() -> new Outcome.Pending(create(request, binding)));
    return outcome instanceof Outcome.Pending pending ? hold(tenantId, pending.id()) : outcome;
  }

  /**
   * Consumes an approval and records the call it authorises in one transaction: if either fails,
   * neither happens, and only one caller can ever consume a given approval.
   *
   * @param tenantId the tenant
   * @param approvalId the approval
   * @param intent the `CALL_DECIDED` entry
   * @return true when this caller may forward the call
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public boolean consume(UUID tenantId, UUID approvalId, AuditRecord intent) {
    Boolean consumed =
        transactions.execute(status -> {
          if (!repository.consume(tenantId, approvalId, clock.instant())) {
            return false;
          }
          audit.appendWithin(tenantId, intent);
          return true;
        });
    return Boolean.TRUE.equals(consumed);
  }

  /**
   * Records a console user's decision.
   *
   * @param user the approver
   * @param approvalId the approval
   * @param approve true to approve, false to reject
   * @return the approval after the decision
   * @throws NotAnApprover if the user is not in the approval's group
   * @throws IllegalArgumentException if the approval does not exist or is no longer pending
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Approval decide(ConsoleUser user, UUID approvalId, boolean approve) {
    Approval approval =
        repository
            .find(user.tenantId(), approvalId)
            .orElseThrow(() -> new IllegalArgumentException("No such approval"));
    Principal approver =
        principals
            .find(user.tenantId(), user.principalId())
            .orElseThrow(() -> new NotAnApprover("Unknown user"));
    if (approver.kind() != Principal.Kind.USER
        || !approver.groups().contains(approval.approversGroup())) {
      throw new NotAnApprover(
          "Only members of " + approval.approversGroup() + " may decide this approval");
    }
    String state = approve ? Approval.APPROVED : Approval.REJECTED;
    transactions.executeWithoutResult(status -> {
      if (!repository.decide(user.tenantId(), approvalId, state, approver.id(), clock.instant())) {
        throw new IllegalArgumentException("The approval is no longer pending");
      }
      audit.appendWithin(
          user.tenantId(),
          AuditRecord.of("APPROVAL_DECIDED")
              .principal(approval.principalId())
              .tool(null, approval.tool())
              .approval(approvalId)
              .actor(approver.id())
              .reason(state.toUpperCase(Locale.ROOT))
              .bundle(approval.policyBundleHash())
              .build());
    });
    signal(approvalId);
    return repository.find(user.tenantId(), approvalId).orElseThrow();
  }

  /**
   * Lists approvals for the console.
   *
   * @param tenantId the tenant
   * @param state only this state, or all
   * @return the approvals, newest first
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<Approval> list(UUID tenantId, Optional<String> state) {
    return repository.list(tenantId, state);
  }

  /**
   * Expires approvals past their time, recording each; runs every 15 seconds.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Scheduled(fixedDelayString = "${aduana.approvals.expiry-interval:PT15S}")
  public void expireDue() {
    for (Approval approval : repository.expire(clock.instant())) {
      recordExpiry(approval, "EXPIRED");
    }
  }

  /**
   * Expires the open approvals of earlier bundles, because their binding can never match again.
   *
   * @param tenantId the tenant
   * @param activeBundle the bundle now active
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void supersede(UUID tenantId, String activeBundle) {
    for (Approval approval : repository.supersede(tenantId, activeBundle)) {
      recordExpiry(approval, "POLICY_CHANGED");
    }
  }

  /**
   * Creates a pending approval and records it, in one transaction.
   *
   * @param request the call
   * @param binding its binding hash
   * @return the new approval's id
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private UUID create(Request request, String binding) {
    Instant now = clock.instant();
    Approval approval =
        new Approval(
            request.principal().tenantId(),
            UUID.randomUUID(),
            binding,
            request.principal().id(),
            request.tool().id(),
            request.qualifiedTool(),
            request.argsHash(),
            request.argsRedacted(),
            request.bundleHash(),
            request.decision().matchedRules(),
            request.flags(),
            request.decision().approval().approversGroup(),
            Approval.PENDING,
            now,
            now.plus(request.decision().approval().expiresIn()),
            Optional.empty(),
            Optional.empty());
    transactions.executeWithoutResult(status -> {
      repository.insert(approval);
      audit.appendWithin(
          approval.tenantId(),
          AuditRecord.of("APPROVAL_CREATED")
              .principal(approval.principalId())
              .tool(request.tool().serverName(), approval.tool())
              .arguments(approval.argsHash(), approval.argsRedacted())
              .decision("require_approval", approval.ruleIds(), null, approval.policyBundleHash())
              .approval(approval.id())
              .flags(approval.flags())
              .build());
    });
    return approval.id();
  }

  /**
   * Waits for a decision on a pending approval, checking the database every half second so a
   * decision made on another node, or a missed signal, is still seen.
   *
   * @param tenantId the tenant
   * @param approvalId the approval
   * @return the outcome when decided, or pending after the hold timeout
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Outcome hold(UUID tenantId, UUID approvalId) {
    Instant deadline = clock.instant().plus(holdTimeout);
    while (true) {
      Approval approval = repository.find(tenantId, approvalId).orElseThrow();
      Outcome outcome = outcomeOf(approval);
      Duration left = Duration.between(clock.instant(), deadline);
      if (!(outcome instanceof Outcome.Pending) || left.isNegative() || left.isZero()) {
        return outcome;
      }
      CompletableFuture<Void> signal =
          waiters.computeIfAbsent(approvalId, id -> new CompletableFuture<>());
      try {
        signal.get(Math.min(left.toMillis(), RECHECK.toMillis()), TimeUnit.MILLISECONDS);
      } catch (TimeoutException | ExecutionException exception) {
        continue;
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return outcome;
      }
    }
  }

  /**
   * Wakes the calls waiting on an approval.
   *
   * @param approvalId the approval
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private void signal(UUID approvalId) {
    Optional.ofNullable(waiters.remove(approvalId)).ifPresent(future -> future.complete(null));
  }

  /**
   * Records an expiry and wakes waiters.
   *
   * @param approval the expired approval
   * @param reason EXPIRED or POLICY_CHANGED
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private void recordExpiry(Approval approval, String reason) {
    audit.append(
        approval.tenantId(),
        AuditRecord.of("APPROVAL_EXPIRED")
            .principal(approval.principalId())
            .tool(null, approval.tool())
            .approval(approval.id())
            .reason(reason)
            .bundle(approval.policyBundleHash())
            .build());
    signal(approval.id());
  }

  /**
   * Maps a stored approval to an outcome; expired and consumed ones count as pending, which makes
   * the caller ask for a new approval on its next try.
   *
   * @param approval the approval
   * @return the outcome
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Outcome outcomeOf(Approval approval) {
    return switch (approval.state()) {
      case Approval.APPROVED -> new Outcome.Approved(approval.id());
      case Approval.REJECTED -> new Outcome.Rejected(approval.id());
      default -> new Outcome.Pending(approval.id());
    };
  }
}
