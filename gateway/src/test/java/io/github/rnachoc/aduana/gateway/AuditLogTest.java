// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rnachoc.aduana.audit.ChainVerifier;
import io.github.rnachoc.aduana.audit.Checkpoints;
import io.github.rnachoc.aduana.gateway.audit.AuditFilter;
import io.github.rnachoc.aduana.gateway.audit.AuditLog;
import io.github.rnachoc.aduana.gateway.audit.AuditRecord;
import io.github.rnachoc.aduana.gateway.identity.ConsoleUser;
import io.github.rnachoc.aduana.gateway.identity.ConsoleUserService;
import io.github.rnachoc.aduana.gateway.identity.PrincipalService;
import io.github.rnachoc.aduana.gateway.identity.Tenants;
import io.github.rnachoc.aduana.gateway.support.Agents;
import io.github.rnachoc.aduana.gateway.support.ApiTestClient;
import io.github.rnachoc.aduana.gateway.support.FakeUpstream;
import io.github.rnachoc.aduana.gateway.support.McpTestClient;
import io.github.rnachoc.aduana.gateway.support.TestDatabase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Phase 5: the chain stays whole under concurrency, refuses changes, fails closed, and verifies.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aduana.master-key=local-only",
      "aduana.catalog.sync-interval=PT1H",
      "aduana.audit.checkpoint-every=25",
      "aduana.policy.directory=src/test/resources/policies/allow-all"
    })
@Import(TestDatabase.class)
class AuditLogTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ADMIN = "audit-admin-" + UUID.randomUUID() + "@example.com";
  private static final String PASSWORD = "correct horse battery";
  private static final FakeUpstream LEDGER = start();

  @LocalServerPort private int port;
  @Autowired private AuditLog audit;
  @Autowired private PrincipalService principals;
  @Autowired private ConsoleUserService users;
  @Autowired private JdbcClient jdbc;
  private McpTestClient agent;
  private ApiTestClient admin;

  /**
   * Starts a fake upstream with one tool.
   *
   * @return the upstream
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static FakeUpstream start() {
    try {
      return new FakeUpstream().withEchoTool("post", "Post an entry.", "memo");
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Registers the fake ledger as an upstream.
   *
   * @param registry the property registry
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("aduana.upstreams[0].name", () -> "ledger");
    registry.add("aduana.upstreams[0].endpoint", LEDGER::url);
  }

  /**
   * Stops the fake upstream.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @AfterAll
  static void stop() {
    LEDGER.close();
  }

  /**
   * Opens an agent and an administrator.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @BeforeEach
  void setUp() {
    agent =
        new McpTestClient(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .withHeader("Authorization", "Bearer " + Agents.agentKey(principals, Map.of()));
    admin = new ApiTestClient(URI.create("http://127.0.0.1:" + port));
    if (admin.login(ADMIN, PASSWORD).status() != 200) {
      users.create(Tenants.DEFAULT, ADMIN, PASSWORD, ConsoleUser.Role.ADMIN);
      assertThat(admin.login(ADMIN, PASSWORD).status()).isEqualTo(200);
    }
  }

  /**
   * Runs SQL as the database owner.
   *
   * @param sql the statement
   * @throws SQLException if it fails
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static void asOwner(String sql) throws SQLException {
    try (Connection connection = TestDatabase.ownerConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  @Test
  void fifty_threads_appending_leave_no_gap_and_no_fork() throws Exception {
    try (ExecutorService pool = Executors.newFixedThreadPool(50)) {
      List<Callable<AuditLog.Appended>> appends =
          IntStream.range(0, 200)
              .mapToObj(i ->
                  (Callable<AuditLog.Appended>)
                      () ->
                          audit.append(
                              Tenants.DEFAULT,
                              AuditRecord.of("TOOL_CHANGED").reason("R" + i).build()))
              .toList();
      List<Long> seqs = new ArrayList<>();
      for (var future : pool.invokeAll(appends)) {
        seqs.add(future.get().seq());
      }
      assertThat(seqs).doesNotHaveDuplicates();
    }
    ChainVerifier.Report report = audit.verify(Tenants.DEFAULT);
    assertThat(report.problems()).isEmpty();
    assertThat(report.lastSeq()).isGreaterThanOrEqualTo(200);
  }

  @Test
  void an_entry_is_committed_when_append_returns_even_under_concurrency() throws Exception {
    try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
      List<Callable<Boolean>> checks =
          IntStream.range(0, 40)
              .mapToObj(i ->
                  (Callable<Boolean>)
                      () -> {
                        long seq =
                            audit
                                .append(
                                    Tenants.DEFAULT,
                                    AuditRecord.of("TOOL_CHANGED").reason("C" + i).build())
                                .seq();
                        try (Connection connection = TestDatabase.ownerConnection();
                            var statement =
                                connection.prepareStatement(
                                    "select count(*) from audit_entry where seq = ? and reason_code = ?")) {
                          statement.setLong(1, seq);
                          statement.setString(2, "C" + i);
                          try (var rows = statement.executeQuery()) {
                            return rows.next() && rows.getLong(1) == 1;
                          }
                        }
                      })
              .toList();
      for (var future : pool.invokeAll(checks)) {
        assertThat(future.get()).isTrue();
      }
    }
  }

  @Test
  void the_application_role_cannot_update_or_delete_entries() {
    audit.append(Tenants.DEFAULT, AuditRecord.of("TOOL_CHANGED").build());
    assertThatThrownBy(() -> jdbc.sql("update audit_entry set type = 'X' where seq = 1").update())
        .hasStackTraceContaining("permission denied");
    assertThatThrownBy(() -> jdbc.sql("delete from audit_entry where seq = 1").update())
        .hasStackTraceContaining("permission denied");
    assertThatThrownBy(() -> jdbc.sql("truncate audit_entry").update())
        .hasStackTraceContaining("permission denied");
  }

  @Test
  void even_the_owner_cannot_update_delete_or_truncate_entries() {
    audit.append(Tenants.DEFAULT, AuditRecord.of("TOOL_CHANGED").build());
    audit.append(Tenants.DEFAULT, AuditRecord.of("TOOL_CHANGED").build());
    audit.checkpoint(Tenants.DEFAULT);
    assertThatThrownBy(() -> asOwner("update audit_entry set type = 'KEY_CREATED' where seq = 1"))
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> asOwner("delete from audit_entry where seq = 1"))
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> asOwner("truncate audit_entry cascade"))
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> asOwner("update audit_checkpoint set hash = 'x'"))
        .hasMessageContaining("append-only");
  }

  @Test
  void when_the_log_cannot_be_written_nothing_reaches_the_upstream() throws SQLException {
    int before = LEDGER.toolCallCount();
    asOwner("revoke insert on audit_entry from aduana_app");
    try {
      JsonNode result =
          agent.callTool("ledger__post", JSON.createObjectNode().put("memo", "x")).body();
      assertThat(result.path("result").path("structuredContent").path("code").asString())
          .isEqualTo("AUDIT_UNAVAILABLE");
    } finally {
      asOwner("grant insert on audit_entry to aduana_app");
    }
    assertThat(LEDGER.toolCallCount()).isEqualTo(before);
  }

  @Test
  void a_call_is_recorded_as_decided_then_completed_with_hashes_of_what_passed() {
    ObjectNode arguments = JSON.createObjectNode().put("memo", "pay rent");
    agent.callTool("ledger__post", arguments);
    List<ObjectNode> last = lastEntries(2);
    assertThat(last.get(0).path("type").asString()).isEqualTo("CALL_DECIDED");
    assertThat(last.get(0).path("decision").asString()).isEqualTo("allow");
    assertThat(last.get(0).path("args_hash").asString()).isEqualTo(AuditLog.hashOf(arguments));
    assertThat(last.get(0).path("policy_bundle_hash").asString())
        .hasSize(64)
        .isNotEqualTo(AuditRecord.NO_BUNDLE);
    assertThat(last.get(1).path("type").asString()).isEqualTo("CALL_COMPLETED");
    assertThat(last.get(1).path("result_hash").asString()).hasSize(64);
    assertThat(last.get(1).path("latency_ms").isNumber()).isTrue();
  }

  @Test
  void the_exported_log_and_checkpoints_verify_with_the_published_key() {
    for (int index = 0; index < 30; index++) {
      audit.append(Tenants.DEFAULT, AuditRecord.of("TOOL_CHANGED").build());
    }
    List<JsonNode> lines = jsonLines(raw("/api/v1/audit/export"));
    List<JsonNode> checkpoints = jsonLines(raw("/api/v1/audit/export?checkpoints=true"));
    assertThat(checkpoints).isNotEmpty();
    JsonNode key =
        new ApiTestClient(URI.create("http://127.0.0.1:" + port))
            .send("GET", "/.well-known/aduana-audit-key", null)
            .body();
    var publicKey = Checkpoints.publicKey(Base64.getDecoder().decode(key.path("spki").asString()));
    ChainVerifier.Report report = ChainVerifier.verify(lines, checkpoints, Optional.of(publicKey));
    assertThat(report.problems()).isEmpty();
    assertThat(admin.send("GET", "/api/v1/audit/verify", null).body().path("problems").size())
        .isZero();
  }

  @Test
  void an_owner_who_bypasses_the_trigger_is_caught_by_verification() throws SQLException {
    audit.append(Tenants.DEFAULT, AuditRecord.of("TOOL_CHANGED").reason("ORIGINAL").build());
    long seq = lastEntries(1).getFirst().path("seq").asLong();
    asOwner("alter table audit_entry disable trigger audit_entry_append_only");
    try {
      asOwner("update audit_entry set reason_code = 'FORGED' where seq = " + seq);
    } finally {
      asOwner("alter table audit_entry enable trigger audit_entry_append_only");
    }
    List<String> kinds =
        audit.verify(Tenants.DEFAULT).problems().stream().map(ChainVerifier.Problem::kind).toList();
    assertThat(kinds).contains("HASH_MISMATCH");
    asOwner("alter table audit_entry disable trigger audit_entry_append_only");
    try {
      asOwner("update audit_entry set reason_code = 'ORIGINAL' where seq = " + seq);
    } finally {
      asOwner("alter table audit_entry enable trigger audit_entry_append_only");
    }
    assertThat(audit.verify(Tenants.DEFAULT).problems()).isEmpty();
  }

  @Test
  void administration_events_are_chained_too() {
    String principal =
        admin
            .send(
                "POST",
                "/api/v1/principals",
                "{\"name\":\"logged-" + UUID.randomUUID().toString().substring(0, 6) + "\"}")
            .body()
            .path("id")
            .asString();
    admin.send("POST", "/api/v1/principals/" + principal + "/keys", "{}");
    ObjectNode last = lastEntries(1).getFirst();
    assertThat(last.path("type").asString()).isEqualTo("KEY_CREATED");
    assertThat(last.path("actor_id").isString()).isTrue();
    assertThat(last.path("args_redacted").path("principal_id").asString()).isEqualTo(principal);
  }

  @Test
  void viewers_can_page_through_the_log() {
    JsonNode page = admin.send("GET", "/api/v1/audit?from_seq=1&limit=3", null).body();
    assertThat(page.size()).isEqualTo(3);
    assertThat(page.get(0).path("seq").asLong()).isEqualTo(1);
  }

  @Test
  void the_head_names_the_newest_entry_without_verifying_the_chain() {
    long seq =
        audit.append(Tenants.DEFAULT, AuditRecord.of("TOOL_CHANGED").reason("HEAD").build()).seq();
    JsonNode head = admin.send("GET", "/api/v1/audit/head", null).body();
    assertThat(head.path("lastSeq").asLong()).isGreaterThanOrEqualTo(seq);
    assertThat(head.path("ts").asString()).isNotBlank();
  }

  /**
   * Reads the newest entries other than checkpoints, oldest first.
   *
   * @param count how many
   * @return the entries
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private List<ObjectNode> lastEntries(int count) {
    List<ObjectNode> all =
        audit.entries(Tenants.DEFAULT, AuditFilter.all()).stream()
            .filter(entry -> !"CHECKPOINT".equals(entry.path("type").asString()))
            .toList();
    return all.subList(all.size() - count, all.size());
  }

  /**
   * Fetches a JSON Lines body with the administrator's session.
   *
   * @param path the path
   * @return the raw text
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private String raw(String path) {
    return admin.sendRaw("GET", path);
  }

  /**
   * Parses JSON Lines.
   *
   * @param text the body
   * @return one node per non-empty line
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static List<JsonNode> jsonLines(String text) {
    return text.lines().filter(line -> !line.isBlank()).map(JSON::readTree).toList();
  }
}
