// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.approval;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * A request for a person to approve one exact call.
 *
 * <p>The approver sees the tool, the caller, the redacted arguments, the matching rules and the
 * marks; there is no free-text justification from the agent, because that text would come from the
 * model the approval is meant to check.
 *
 * @param tenantId the tenant
 * @param id the approval id
 * @param bindingHash SHA-256 of tenant, principal, tool, arguments hash and policy bundle hash
 * @param principalId the agent that asked
 * @param toolId the catalog tool
 * @param tool the agent-facing tool name
 * @param argsHash hash of the original arguments
 * @param argsRedacted the arguments as the approver sees them
 * @param policyBundleHash the bundle that asked for approval
 * @param ruleIds the rules that matched
 * @param flags marks such as `FLOW_FROM_UNTRUSTED`
 * @param approversGroup the group whose members may decide
 * @param state pending, approved, rejected, expired or consumed
 * @param createdAt when it was requested
 * @param expiresAt when it stops being usable
 * @param decidedBy the principal of the console user who decided
 * @param decidedAt when it was decided
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record Approval(
    UUID tenantId,
    UUID id,
    String bindingHash,
    UUID principalId,
    UUID toolId,
    String tool,
    String argsHash,
    JsonNode argsRedacted,
    String policyBundleHash,
    List<String> ruleIds,
    List<String> flags,
    String approversGroup,
    String state,
    Instant createdAt,
    Instant expiresAt,
    Optional<UUID> decidedBy,
    Optional<Instant> decidedAt) {

  /** Waiting for a decision. */
  public static final String PENDING = "pending";

  /** Approved and not used yet. */
  public static final String APPROVED = "approved";

  /** Rejected by an approver. */
  public static final String REJECTED = "rejected";

  /** Past its expiry, or made obsolete by a new policy, before it was used. */
  public static final String EXPIRED = "expired";

  /** Used by exactly one call. */
  public static final String CONSUMED = "consumed";

  /**
   * Copies the lists.
   *
   * @param tenantId the tenant
   * @param id the id
   * @param bindingHash the binding hash
   * @param principalId the agent
   * @param toolId the tool
   * @param tool the tool name
   * @param argsHash the arguments hash
   * @param argsRedacted the redacted arguments
   * @param policyBundleHash the bundle
   * @param ruleIds the rules
   * @param flags the marks
   * @param approversGroup the group
   * @param state the state
   * @param createdAt when requested
   * @param expiresAt when it expires
   * @param decidedBy who decided
   * @param decidedAt when decided
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Approval {
    ruleIds = List.copyOf(ruleIds);
    flags = List.copyOf(flags);
  }
}
