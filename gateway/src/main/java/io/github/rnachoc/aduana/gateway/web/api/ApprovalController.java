// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.web.api;

import io.github.rnachoc.aduana.gateway.approval.Approval;
import io.github.rnachoc.aduana.gateway.approval.ApprovalService;
import io.github.rnachoc.aduana.gateway.identity.ConsoleUser;
import io.github.rnachoc.aduana.gateway.identity.Principal;
import io.github.rnachoc.aduana.gateway.identity.PrincipalService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The approvals queue: what is waiting, and the approve and reject actions.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {

  private final ApprovalService approvals;
  private final PrincipalService principals;

  /**
   * An approval as the approver sees it: the call, the rules and the marks, and no free text from
   * the agent.
   *
   * @param id the approval
   * @param state its state
   * @param tool the agent-facing tool name
   * @param principal the agent's name
   * @param principalAttributes the agent's attributes
   * @param arguments the redacted arguments
   * @param rules the rules that asked for approval
   * @param flags marks such as `FLOW_FROM_UNTRUSTED`
   * @param approversGroup who may decide
   * @param createdAt when requested
   * @param expiresAt when it expires
   * @param decidedAt when decided, if it was
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record ApprovalView(
      UUID id,
      String state,
      String tool,
      String principal,
      java.util.Map<String, String> principalAttributes,
      JsonNode arguments,
      List<String> rules,
      List<String> flags,
      String approversGroup,
      Instant createdAt,
      Instant expiresAt,
      Instant decidedAt) {}

  /**
   * Creates the controller.
   *
   * @param approvals the approval service
   * @param principals names the agents
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ApprovalController(ApprovalService approvals, PrincipalService principals) {
    this.approvals = approvals;
    this.principals = principals;
  }

  /**
   * Lists approvals.
   *
   * @param user the signed-in approver
   * @param state only this state
   * @return the approvals, newest first
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @GetMapping
  public List<ApprovalView> list(
      @AuthenticationPrincipal ConsoleUser user, @RequestParam Optional<String> state) {
    return approvals.list(user.tenantId(), state).stream().map(this::view).toList();
  }

  /**
   * Approves a pending call.
   *
   * @param user the signed-in approver
   * @param id the approval
   * @return the approval after the decision
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @PostMapping("/{id}/approve")
  public ApprovalView approve(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id) {
    return view(approvals.decide(user, id, true));
  }

  /**
   * Rejects a pending call.
   *
   * @param user the signed-in approver
   * @param id the approval
   * @return the approval after the decision
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @PostMapping("/{id}/reject")
  public ApprovalView reject(@AuthenticationPrincipal ConsoleUser user, @PathVariable UUID id) {
    return view(approvals.decide(user, id, false));
  }

  /**
   * Shows an approval.
   *
   * @param approval the approval
   * @return the view
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ApprovalView view(Approval approval) {
    Optional<Principal> agent =
        principals.list(approval.tenantId()).stream()
            .filter(principal -> principal.id().equals(approval.principalId()))
            .findFirst();
    return new ApprovalView(
        approval.id(),
        approval.state(),
        approval.tool(),
        agent.map(Principal::name).orElse("unknown"),
        agent.map(Principal::attributes).orElse(java.util.Map.of()),
        approval.argsRedacted(),
        approval.ruleIds(),
        approval.flags(),
        approval.approversGroup(),
        approval.createdAt(),
        approval.expiresAt(),
        approval.decidedAt().orElse(null));
  }
}
