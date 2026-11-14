// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.approval;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicit SQL for approvals; every state change is a conditional update, so two deciders or two
 * consumers can never both succeed.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Repository
public class ApprovalRepository {

  private final JdbcClient jdbc;
  private final JsonMapper json;

  /**
   * Creates the repository.
   *
   * @param jdbc the JDBC client
   * @param json the shared JSON mapper
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ApprovalRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  /**
   * Inserts a pending approval.
   *
   * @param approval the approval
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public void insert(Approval approval) {
    jdbc.sql(
            """
            insert into approval (tenant_id, id, binding_hash, principal_id, tool_id, tool, args_hash,
              args_redacted, policy_bundle_hash, rule_ids, flags, approvers_group, state, created_at,
              expires_at)
            values (:tenant, :id, :binding, :principal, :toolId, :tool, :argsHash, cast(:args as jsonb),
              :bundle, :rules, :flags, :group, :state, :created, :expires)
            """)
        .param("tenant", approval.tenantId())
        .param("id", approval.id())
        .param("binding", approval.bindingHash())
        .param("principal", approval.principalId())
        .param("toolId", approval.toolId())
        .param("tool", approval.tool())
        .param("argsHash", approval.argsHash())
        .param("args", approval.argsRedacted().toString())
        .param("bundle", approval.policyBundleHash())
        .param("rules", approval.ruleIds().toArray(String[]::new))
        .param("flags", approval.flags().toArray(String[]::new))
        .param("group", approval.approversGroup())
        .param("state", approval.state())
        .param("created", utc(approval.createdAt()))
        .param("expires", utc(approval.expiresAt()))
        .update();
  }

  /**
   * Finds the newest approval for a binding.
   *
   * @param tenantId the tenant
   * @param bindingHash the binding
   * @return the approval, if any
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<Approval> latest(UUID tenantId, String bindingHash) {
    return jdbc.sql(
            """
            select * from approval where tenant_id = :tenant and binding_hash = :binding
            order by created_at desc limit 1
            """)
        .param("tenant", tenantId)
        .param("binding", bindingHash)
        .query(this::map)
        .optional();
  }

  /**
   * Finds an approval by id.
   *
   * @param tenantId the tenant
   * @param id the approval
   * @return the approval, if any
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<Approval> find(UUID tenantId, UUID id) {
    return jdbc.sql("select * from approval where tenant_id = :tenant and id = :id")
        .param("tenant", tenantId)
        .param("id", id)
        .query(this::map)
        .optional();
  }

  /**
   * Lists approvals, newest first.
   *
   * @param tenantId the tenant
   * @param state only this state, or all
   * @return the approvals
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<Approval> list(UUID tenantId, Optional<String> state) {
    return jdbc.sql(
            """
            select * from approval where tenant_id = :tenant
              and (cast(:state as text) is null or state = cast(:state as text))
            order by created_at desc limit 500
            """)
        .param("tenant", tenantId)
        .param("state", state.orElse(null))
        .query(this::map)
        .list();
  }

  /**
   * Records a decision on a pending, unexpired approval.
   *
   * @param tenantId the tenant
   * @param id the approval
   * @param state approved or rejected
   * @param decidedBy the approver's principal
   * @param now the time
   * @return true if this call made the decision
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public boolean decide(UUID tenantId, UUID id, String state, UUID decidedBy, Instant now) {
    return jdbc.sql(
                """
                update approval set state = :state, decided_by = :by, decided_at = :now
                where tenant_id = :tenant and id = :id and state = 'pending' and expires_at > :now
                """)
            .param("state", state)
            .param("by", decidedBy)
            .param("now", utc(now))
            .param("tenant", tenantId)
            .param("id", id)
            .update()
        == 1;
  }

  /**
   * Uses an approved approval once: only one caller can ever get true for the same approval.
   *
   * @param tenantId the tenant
   * @param id the approval
   * @param now the time
   * @return true if this caller consumed it
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public boolean consume(UUID tenantId, UUID id, Instant now) {
    return jdbc.sql(
            """
            update approval set state = 'consumed', consumed_at = :now
            where tenant_id = :tenant and id = :id and state = 'approved' and expires_at > :now
            returning id
            """)
        .param("now", utc(now))
        .param("tenant", tenantId)
        .param("id", id)
        .query(UUID.class)
        .optional()
        .isPresent();
  }

  /**
   * Expires pending and approved approvals past their time.
   *
   * @param now the time
   * @return the approvals that expired
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<Approval> expire(Instant now) {
    return jdbc.sql(
            """
            update approval set state = 'expired'
            where state in ('pending', 'approved') and expires_at <= :now
            returning *
            """)
        .param("now", utc(now))
        .query(this::map)
        .list();
  }

  /**
   * Expires the open approvals requested under any bundle other than the active one.
   *
   * @param tenantId the tenant
   * @param activeBundle the bundle now active
   * @return the approvals that were superseded
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<Approval> supersede(UUID tenantId, String activeBundle) {
    return jdbc.sql(
            """
            update approval set state = 'expired'
            where tenant_id = :tenant and state in ('pending', 'approved')
              and policy_bundle_hash <> :bundle
            returning *
            """)
        .param("tenant", tenantId)
        .param("bundle", activeBundle)
        .query(this::map)
        .list();
  }

  /**
   * Maps a row.
   *
   * @param rs the result set
   * @param row the row number
   * @return the approval
   * @throws SQLException if a column cannot be read
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Approval map(ResultSet rs, int row) throws SQLException {
    return new Approval(
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("id", UUID.class),
        rs.getString("binding_hash"),
        rs.getObject("principal_id", UUID.class),
        rs.getObject("tool_id", UUID.class),
        rs.getString("tool"),
        rs.getString("args_hash"),
        json.readTree(rs.getString("args_redacted")),
        rs.getString("policy_bundle_hash"),
        Arrays.asList((String[]) rs.getArray("rule_ids").getArray()),
        Arrays.asList((String[]) rs.getArray("flags").getArray()),
        rs.getString("approvers_group"),
        rs.getString("state"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
        Optional.ofNullable(rs.getObject("decided_by", UUID.class)),
        Optional.ofNullable(rs.getObject("decided_at", OffsetDateTime.class))
            .map(OffsetDateTime::toInstant));
  }

  /**
   * Converts an instant for the driver.
   *
   * @param instant the instant
   * @return the same instant at UTC
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
