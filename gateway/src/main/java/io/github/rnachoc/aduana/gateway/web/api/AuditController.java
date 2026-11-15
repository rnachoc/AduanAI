// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.web.api;

import io.github.rnachoc.aduana.audit.ChainVerifier;
import io.github.rnachoc.aduana.gateway.audit.AuditFilter;
import io.github.rnachoc.aduana.gateway.audit.AuditLog;
import io.github.rnachoc.aduana.gateway.identity.ConsoleUser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads, verifies and exports the audit log.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

  private static final MediaType JSON_LINES = MediaType.parseMediaType("application/x-ndjson");
  private static final int PAGE = 200;

  private final AuditLog audit;

  /**
   * Creates the controller.
   *
   * @param audit the audit log
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public AuditController(AuditLog audit) {
    this.audit = audit;
  }

  /**
   * Returns one page of entries by `seq`, optionally filtered.
   *
   * @param user the signed-in viewer
   * @param fromSeq the first seq
   * @param limit the page size, at most 200
   * @param principal only this principal's entries
   * @param tool only this tool's entries
   * @param decision only entries with this decision
   * @return the entries
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @GetMapping
  public List<ObjectNode> entries(
      @AuthenticationPrincipal ConsoleUser user,
      @RequestParam(name = "from_seq", defaultValue = "1") long fromSeq,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam Optional<UUID> principal,
      @RequestParam Optional<String> tool,
      @RequestParam Optional<String> decision) {
    AuditFilter filter =
        new AuditFilter(
            fromSeq,
            Math.clamp(limit, 1, PAGE),
            principal,
            tool,
            decision,
            Optional.empty(),
            Optional.empty());
    return audit.entries(user.tenantId(), filter);
  }

  /**
   * Verifies the chain and checkpoints as stored.
   *
   * @param user the signed-in viewer
   * @return the verification report
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @GetMapping("/verify")
  public Verification verify(@AuthenticationPrincipal ConsoleUser user) {
    ChainVerifier.Report report = audit.verify(user.tenantId());
    List<ObjectNode> checkpoints = audit.checkpoints(user.tenantId());
    Optional<LastCheckpoint> last =
        checkpoints.isEmpty()
            ? Optional.empty()
            : Optional.of(
                new LastCheckpoint(
                    checkpoints.getLast().path("seq").asLong(),
                    checkpoints.getLast().path("ts").asString()));
    return new Verification(
        report.entries(),
        report.lastSeq(),
        report.checkpoints(),
        report.problems(),
        report.problems().isEmpty(),
        last.orElse(null));
  }

  /**
   * The chain's verification, as the CLI and the console read it.
   *
   * @param entries entries checked
   * @param lastSeq the last `seq`
   * @param checkpoints checkpoints checked
   * @param problems what is wrong; empty when intact
   * @param intact true when there are no problems
   * @param lastCheckpoint the newest checkpoint, or null
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Verification(
      long entries,
      long lastSeq,
      long checkpoints,
      List<ChainVerifier.Problem> problems,
      boolean intact,
      LastCheckpoint lastCheckpoint) {}

  /**
   * The newest signed checkpoint.
   *
   * @param seq the entry it covers
   * @param ts when it was signed
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record LastCheckpoint(long seq, String ts) {}

  /**
   * Exports entries (or, with `checkpoints=true`, checkpoints) as JSON Lines, one object per line.
   *
   * @param user the signed-in administrator
   * @param from only entries at or after this RFC 3339 time
   * @param to only entries at or before this RFC 3339 time
   * @param checkpoints export the checkpoints instead of the entries
   * @return the JSON Lines body
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @GetMapping("/export")
  public ResponseEntity<String> export(
      @AuthenticationPrincipal ConsoleUser user,
      @RequestParam Optional<String> from,
      @RequestParam Optional<String> to,
      @RequestParam(defaultValue = "false") boolean checkpoints) {
    List<ObjectNode> lines =
        checkpoints
            ? audit.checkpoints(user.tenantId())
            : audit.entries(
                user.tenantId(),
                new AuditFilter(
                    1,
                    AuditFilter.MAX_LIMIT,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    from,
                    to));
    String body =
        lines.stream().map(ObjectNode::toString).collect(Collectors.joining("\n", "", "\n"));
    return ResponseEntity.ok().contentType(JSON_LINES).body(lines.isEmpty() ? "" : body);
  }
}
