// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.aduana.gateway.audit.AuditFilter;
import io.github.rnachoc.aduana.gateway.audit.AuditLog;
import io.github.rnachoc.aduana.gateway.identity.ConsoleUser;
import io.github.rnachoc.aduana.gateway.identity.ConsoleUserService;
import io.github.rnachoc.aduana.gateway.identity.PrincipalService;
import io.github.rnachoc.aduana.gateway.identity.Tenants;
import io.github.rnachoc.aduana.gateway.policy.PolicyService;
import io.github.rnachoc.aduana.gateway.policy.PolicySource;
import io.github.rnachoc.aduana.gateway.support.Agents;
import io.github.rnachoc.aduana.gateway.support.ApiTestClient;
import io.github.rnachoc.aduana.gateway.support.FakeUpstream;
import io.github.rnachoc.aduana.gateway.support.McpTestClient;
import io.github.rnachoc.aduana.gateway.support.TestDatabase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Phase 6: approvals bind to the exact call, are used once, expire, and only the right people can
 * give them.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aduana.master-key=local-only",
      "aduana.catalog.sync-interval=PT1H",
      "aduana.approvals.hold-timeout=PT2S",
      "aduana.approvals.expiry-interval=PT1S",
      "aduana.policy.directory=src/test/resources/policies/approvals"
    })
@Import(TestDatabase.class)
class ApprovalTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String PASSWORD = "correct horse battery";
  private static final String FINANCE = "finance-" + UUID.randomUUID() + "@example.com";
  private static final String SECURITY = "security-" + UUID.randomUUID() + "@example.com";
  private static final FakeUpstream BANK = start();

  @LocalServerPort private int port;
  @Autowired private PrincipalService principals;
  @Autowired private ConsoleUserService users;
  @Autowired private PolicyService policies;
  @Autowired private AuditLog audit;
  private String agentKey;
  private McpTestClient agent;
  private ApiTestClient finance;

  /**
   * Starts the fake bank.
   *
   * @return the upstream
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static FakeUpstream start() {
    try {
      return new FakeUpstream()
          .withEchoTool("pay", "Pay someone.", "to", "amount:integer")
          .withEchoTool("quick", "A payment whose approval lasts four seconds.", "to");
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Registers the bank.
   *
   * @param registry the property registry
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("aduana.upstreams[0].name", () -> "bank");
    registry.add("aduana.upstreams[0].endpoint", BANK::url);
  }

  /**
   * Stops the fake bank.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @AfterAll
  static void stop() {
    BANK.close();
  }

  /**
   * A fresh agent for every test, so no approval carries over, and a finance approver.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @BeforeEach
  void setUp() {
    agentKey = Agents.agentKey(principals, Map.of("team", "support"));
    agent = client(agentKey);
    finance = approver(FINANCE, "finance");
  }

  /**
   * Opens an agent client.
   *
   * @param key the API key
   * @return the client
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private McpTestClient client(String key) {
    return new McpTestClient(URI.create("http://127.0.0.1:" + port + "/mcp"))
        .withHeader("Authorization", "Bearer " + key);
  }

  /**
   * Signs in an approver of one group, creating the user the first time.
   *
   * @param email the address
   * @param group the approver group
   * @return the session
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ApiTestClient approver(String email, String group) {
    ApiTestClient session = new ApiTestClient(URI.create("http://127.0.0.1:" + port));
    if (session.login(email, PASSWORD).status() != 200) {
      users.create(Tenants.DEFAULT, email, PASSWORD, ConsoleUser.Role.APPROVER, List.of(group));
      assertThat(session.login(email, PASSWORD).status()).isEqualTo(200);
    }
    return session;
  }

  /**
   * Calls `bank__pay`.
   *
   * @param client the agent
   * @param to the payee
   * @param amount the amount
   * @return the result
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static JsonNode pay(McpTestClient client, String to, int amount) {
    ObjectNode arguments = JSON.createObjectNode().put("to", to).put("amount", amount);
    return client.callTool("bank__pay", arguments).body().path("result");
  }

  /**
   * Reads the error code of a result.
   *
   * @param result the result
   * @return the code, or an empty string for a success
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String code(JsonNode result) {
    return result.path("isError").asBoolean()
        ? result.path("structuredContent").path("code").asString()
        : "";
  }

  /**
   * Reads the approval id of a pending result.
   *
   * @param result the result
   * @return the approval id
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String approvalId(JsonNode result) {
    return result.path("structuredContent").path("approval_id").asString();
  }

  @Test
  void a_call_is_held_and_goes_ahead_when_finance_approves_during_the_hold() throws Exception {
    int before = BANK.toolCallCount();
    JsonNode first = pay(agent, "XX01", 100);
    assertThat(code(first)).isEqualTo("APPROVAL_PENDING");
    String id = approvalId(first);
    CompletableFuture<JsonNode> held = CompletableFuture.supplyAsync(() -> pay(agent, "XX01", 100));
    Thread.sleep(500);
    assertThat(finance.send("POST", "/api/v1/approvals/" + id + "/approve", null).status())
        .isEqualTo(200);
    assertThat(code(held.get())).isEmpty();
    assertThat(BANK.toolCallCount()).isEqualTo(before + 1);
  }

  @Test
  void twenty_parallel_retries_of_an_approved_call_execute_exactly_once() throws Exception {
    String id = approvalId(pay(agent, "XX02", 200));
    finance.send("POST", "/api/v1/approvals/" + id + "/approve", null);
    int before = BANK.toolCallCount();
    try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
      List<Callable<JsonNode>> calls =
          IntStream.range(0, 20)
              .mapToObj(i -> (Callable<JsonNode>) () -> pay(agent, "XX02", 200))
              .toList();
      long succeeded =
          pool.invokeAll(calls).stream()
              .filter(future -> {
                try {
                  return code(future.get()).isEmpty();
                } catch (Exception exception) {
                  throw new IllegalStateException(exception);
                }
              })
              .count();
      assertThat(succeeded).isEqualTo(1);
    }
    assertThat(BANK.toolCallCount()).isEqualTo(before + 1);
  }

  @Test
  void changing_one_character_of_the_arguments_needs_a_new_approval() {
    String id = approvalId(pay(agent, "XX03", 300));
    finance.send("POST", "/api/v1/approvals/" + id + "/approve", null);
    int before = BANK.toolCallCount();
    JsonNode changed = pay(agent, "XX04", 300);
    assertThat(code(changed)).isEqualTo("APPROVAL_PENDING");
    assertThat(approvalId(changed)).isNotEqualTo(id);
    JsonNode oneCent = pay(agent, "XX03", 301);
    assertThat(code(oneCent)).isEqualTo("APPROVAL_PENDING");
    assertThat(BANK.toolCallCount()).isEqualTo(before);
    assertThat(code(pay(agent, "XX03", 300))).isEmpty();
    assertThat(BANK.toolCallCount()).isEqualTo(before + 1);
  }

  @Test
  void an_agent_key_cannot_approve_and_a_user_outside_the_group_is_refused() {
    String id = approvalId(pay(agent, "XX05", 500));
    McpTestClient.Reply byAgent =
        new McpTestClient(
                URI.create("http://127.0.0.1:" + port + "/api/v1/approvals/" + id + "/approve"))
            .withHeader("Authorization", "Bearer " + agentKey)
            .post("{}", Map.of());
    assertThat(byAgent.status()).isIn(401, 403);
    McpTestClient.Reply listing =
        new McpTestClient(URI.create("http://127.0.0.1:" + port + "/api/v1/approvals"))
            .withHeader("Authorization", "Bearer " + agentKey)
            .other("GET");
    assertThat(listing.status()).isIn(401, 403);
    ApiTestClient security = approver(SECURITY, "security");
    assertThat(security.send("POST", "/api/v1/approvals/" + id + "/approve", null).status())
        .isEqualTo(403);
    ApiTestClient anonymous = new ApiTestClient(URI.create("http://127.0.0.1:" + port));
    assertThat(anonymous.send("POST", "/api/v1/approvals/" + id + "/approve", null).status())
        .isIn(401, 403);
    assertThat(code(pay(agent, "XX05", 500))).isEqualTo("APPROVAL_PENDING");
  }

  @Test
  void the_queue_lists_only_approvals_the_user_may_decide() {
    String id = approvalId(pay(agent, "XX07", 700));
    assertThat(finance.send("GET", "/api/v1/approvals?state=pending", null).body().toString())
        .contains(id);
    ApiTestClient security = approver(SECURITY, "security");
    assertThat(security.send("GET", "/api/v1/approvals?state=pending", null).body().toString())
        .doesNotContain(id);
  }

  @Test
  void a_rejected_call_is_answered_rejected_and_never_forwarded() {
    int before = BANK.toolCallCount();
    String id = approvalId(pay(agent, "XX06", 600));
    assertThat(finance.send("POST", "/api/v1/approvals/" + id + "/reject", null).status())
        .isEqualTo(200);
    assertThat(code(pay(agent, "XX06", 600))).isEqualTo("APPROVAL_REJECTED");
    assertThat(finance.send("POST", "/api/v1/approvals/" + id + "/approve", null).status())
        .isEqualTo(409);
    assertThat(BANK.toolCallCount()).isEqualTo(before);
  }

  @Test
  void an_expired_approval_does_not_execute_and_its_expiry_is_recorded()
      throws InterruptedException {
    ObjectNode arguments = JSON.createObjectNode().put("to", "XX07");
    String id = approvalId(agent.callTool("bank__quick", arguments).body().path("result"));
    finance.send("POST", "/api/v1/approvals/" + id + "/approve", null);
    Thread.sleep(Duration.ofSeconds(5));
    int before = BANK.toolCallCount();
    JsonNode retry = agent.callTool("bank__quick", arguments).body().path("result");
    assertThat(code(retry)).isEqualTo("APPROVAL_PENDING");
    assertThat(approvalId(retry)).isNotEqualTo(id);
    assertThat(BANK.toolCallCount()).isEqualTo(before);
    assertThat(audit.entries(Tenants.DEFAULT, AuditFilter.all()))
        .anyMatch(entry ->
            "APPROVAL_EXPIRED".equals(entry.path("type").asString())
                && id.equals(entry.path("approval_id").asString()));
  }

  @Test
  void loading_a_new_policy_invalidates_the_pending_approvals_of_the_old_one() throws IOException {
    String id = approvalId(pay(agent, "XX08", 800));
    policies.load(
        Tenants.DEFAULT,
        PolicySource.read(Path.of("src/test/resources/policies/approvals-v2")),
        Optional.empty());
    try {
      assertThat(finance.send("POST", "/api/v1/approvals/" + id + "/approve", null).status())
          .isEqualTo(409);
      JsonNode again = pay(agent, "XX08", 800);
      assertThat(approvalId(again)).isNotEqualTo(id);
      assertThat(audit.entries(Tenants.DEFAULT, AuditFilter.all()))
          .anyMatch(entry ->
              "APPROVAL_EXPIRED".equals(entry.path("type").asString())
                  && "POLICY_CHANGED".equals(entry.path("reason_code").asString())
                  && id.equals(entry.path("approval_id").asString()));
    } finally {
      policies.load(
          Tenants.DEFAULT,
          PolicySource.read(Path.of("src/test/resources/policies/approvals")),
          Optional.empty());
    }
  }

  @Test
  void the_approver_sees_the_call_the_rules_and_no_agent_text() {
    pay(agent, "XX09", 900);
    JsonNode pending = finance.send("GET", "/api/v1/approvals?state=pending", null).body();
    JsonNode mine =
        pending
            .valueStream()
            .filter(view -> "XX09".equals(view.path("arguments").path("to").asString()))
            .findFirst()
            .orElseThrow();
    assertThat(mine.path("tool").asString()).isEqualTo("bank__pay");
    assertThat(mine.path("rules").toString()).contains("payments-need-finance");
    assertThat(mine.path("approversGroup").asString()).isEqualTo("finance");
    assertThat(mine.path("principalAttributes").path("team").asString()).isEqualTo("support");
    assertThat(mine.has("justification")).isFalse();
  }
}
