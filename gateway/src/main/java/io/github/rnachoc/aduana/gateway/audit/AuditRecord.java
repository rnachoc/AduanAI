// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.audit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * What a caller asks the log to record; the log adds `seq`, `ts`, `prev_hash` and `hash`.
 *
 * @param type the entry type, from the closed list of plan §6.3
 * @param principalId the agent the entry is about
 * @param server the server name
 * @param tool the agent-facing tool name
 * @param argsHash hex SHA-256 of the canonical arguments
 * @param argsRedacted the arguments after redaction, or the details of an administration event
 * @param decision allow, require_approval or deny
 * @param ruleIds the rules that matched
 * @param reasonCode a stable reason code
 * @param policyBundleHash the active bundle; 64 zeros when none
 * @param approvalId the approval involved
 * @param actorId the console user behind an administration event
 * @param resultHash hex SHA-256 of the canonical result
 * @param flags marks such as `POLICY_TYPE_MISMATCH`
 * @param latencyMs how long the upstream call took
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record AuditRecord(
    String type,
    Optional<UUID> principalId,
    Optional<String> server,
    Optional<String> tool,
    Optional<String> argsHash,
    Optional<JsonNode> argsRedacted,
    Optional<String> decision,
    List<String> ruleIds,
    Optional<String> reasonCode,
    String policyBundleHash,
    Optional<UUID> approvalId,
    Optional<UUID> actorId,
    Optional<String> resultHash,
    List<String> flags,
    Optional<Long> latencyMs) {

  /** The bundle hash recorded when no policy is active. */
  public static final String NO_BUNDLE = "0".repeat(64);

  /**
   * Copies the lists.
   *
   * @param type the type
   * @param principalId the principal
   * @param server the server
   * @param tool the tool
   * @param argsHash the arguments hash
   * @param argsRedacted the redacted arguments or details
   * @param decision the decision
   * @param ruleIds the rules
   * @param reasonCode the reason
   * @param policyBundleHash the bundle
   * @param approvalId the approval
   * @param actorId the actor
   * @param resultHash the result hash
   * @param flags the flags
   * @param latencyMs the latency
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public AuditRecord {
    ruleIds = List.copyOf(ruleIds);
    flags = List.copyOf(flags);
  }

  /**
   * Starts a record of a type with every other field empty.
   *
   * @param type the entry type
   * @return a builder
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static Builder of(String type) {
    return new Builder(type);
  }

  /**
   * Fills a record field by field.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static final class Builder {
    private final String type;
    private UUID principalId;
    private String server;
    private String tool;
    private String argsHash;
    private JsonNode argsRedacted;
    private String decision;
    private List<String> ruleIds = List.of();
    private String reasonCode;
    private String policyBundleHash = NO_BUNDLE;
    private UUID approvalId;
    private UUID actorId;
    private String resultHash;
    private List<String> flags = List.of();
    private Long latencyMs;

    /**
     * Starts a builder.
     *
     * @param type the entry type
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    private Builder(String type) {
      this.type = type;
    }

    /**
     * Sets the principal.
     *
     * @param value the principal id
     * @return this builder
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Builder principal(UUID value) {
      principalId = value;
      return this;
    }

    /**
     * Sets the server and the agent-facing tool name.
     *
     * @param serverName the server
     * @param qualifiedTool the tool as `server__tool`
     * @return this builder
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Builder tool(String serverName, String qualifiedTool) {
      server = serverName;
      tool = qualifiedTool;
      return this;
    }

    /**
     * Sets the arguments' hash and redacted form.
     *
     * @param hash the hash of the original arguments
     * @param redacted the redacted arguments
     * @return this builder
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Builder arguments(String hash, JsonNode redacted) {
      argsHash = hash;
      argsRedacted = redacted;
      return this;
    }

    /**
     * Sets the details of an administration event.
     *
     * @param details non-sensitive details
     * @return this builder
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Builder details(JsonNode details) {
      argsRedacted = details;
      return this;
    }

    /**
     * Sets the decision.
     *
     * @param effect the effect name
     * @param rules the matched rules
     * @param reason the reason code, or null
     * @param bundleHash the bundle that decided
     * @return this builder
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Builder decision(String effect, List<String> rules, String reason, String bundleHash) {
      decision = effect;
      ruleIds = rules;
      reasonCode = reason;
      policyBundleHash = bundleHash;
      return this;
    }

    /**
     * Sets a reason code alone.
     *
     * @param reason the reason code
     * @return this builder
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Builder reason(String reason) {
      reasonCode = reason;
      return this;
    }

    /**
     * Sets the bundle hash alone.
     *
     * @param bundleHash the active bundle
     * @return this builder
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Builder bundle(String bundleHash) {
      policyBundleHash = bundleHash;
      return this;
    }

    /**
     * Sets the approval.
     *
     * @param value the approval id
     * @return this builder
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Builder approval(UUID value) {
      approvalId = value;
      return this;
    }

    /**
     * Sets the actor.
     *
     * @param value the console user's principal
     * @return this builder
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Builder actor(UUID value) {
      actorId = value;
      return this;
    }

    /**
     * Sets the result hash and latency.
     *
     * @param hash the result hash
     * @param latency milliseconds
     * @return this builder
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Builder result(String hash, long latency) {
      resultHash = hash;
      latencyMs = latency;
      return this;
    }

    /**
     * Sets the result hash alone.
     *
     * @param hash the hash
     * @return this builder
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Builder resultHash(String hash) {
      resultHash = hash;
      return this;
    }

    /**
     * Sets the latency alone.
     *
     * @param latency milliseconds
     * @return this builder
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Builder latency(long latency) {
      latencyMs = latency;
      return this;
    }

    /**
     * Sets the flags.
     *
     * @param values the flags
     * @return this builder
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Builder flags(List<String> values) {
      flags = values;
      return this;
    }

    /**
     * Builds the record.
     *
     * @return the record
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public AuditRecord build() {
      return new AuditRecord(
          type,
          Optional.ofNullable(principalId),
          Optional.ofNullable(server),
          Optional.ofNullable(tool),
          Optional.ofNullable(argsHash),
          Optional.ofNullable(argsRedacted),
          Optional.ofNullable(decision),
          ruleIds,
          Optional.ofNullable(reasonCode),
          policyBundleHash,
          Optional.ofNullable(approvalId),
          Optional.ofNullable(actorId),
          Optional.ofNullable(resultHash),
          flags,
          Optional.ofNullable(latencyMs));
    }
  }
}
