// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.aduana.gateway.audit.AuditFilter;
import io.github.rnachoc.aduana.gateway.audit.AuditLog;
import io.github.rnachoc.aduana.gateway.catalog.CatalogService;
import io.github.rnachoc.aduana.gateway.catalog.CatalogTool;
import io.github.rnachoc.aduana.gateway.config.AduanaProperties;
import io.github.rnachoc.aduana.gateway.identity.PrincipalService;
import io.github.rnachoc.aduana.gateway.identity.Tenants;
import io.github.rnachoc.aduana.gateway.limits.LimitService;
import io.github.rnachoc.aduana.gateway.support.Agents;
import io.github.rnachoc.aduana.gateway.support.FakeUpstream;
import io.github.rnachoc.aduana.gateway.support.McpTestClient;
import io.github.rnachoc.aduana.gateway.support.TestDatabase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Phase 8: rates, budgets, result size and idempotency keys.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aduana.master-key=local-only",
      "aduana.catalog.sync-interval=PT1H",
      "aduana.limits.calls-per-minute-per-tool=3",
      "aduana.limits.calls-per-minute-per-principal=50",
      "aduana.limits.daily-budget=10",
      "aduana.limits.max-result-bytes=2000",
      "aduana.policy.directory=src/test/resources/policies/allow-all"
    })
@Import(TestDatabase.class)
class LimitsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SERVER = "lim";
  private static final FakeUpstream UPSTREAM = start();

  @LocalServerPort private int port;
  @Autowired private PrincipalService principals;
  @Autowired private CatalogService catalog;
  @Autowired private AuditLog audit;
  @Autowired private JdbcClient jdbc;
  @Autowired private AduanaProperties properties;
  @Autowired private PlatformTransactionManager transactionManager;
  private McpTestClient agent;

  /**
   * Starts the fake upstream.
   *
   * @return the upstream
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static FakeUpstream start() {
    try {
      return new FakeUpstream()
          .withEchoTool("echo", "Echo the text.", "text")
          .withEchoTool("costly", "A tool that costs four units.", "text")
          .withEchoTool("pay", "Pay someone.", "to", "amount:integer");
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Registers the upstream.
   *
   * @param registry the property registry
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("aduana.upstreams[0].name", () -> SERVER);
    registry.add("aduana.upstreams[0].endpoint", UPSTREAM::url);
  }

  /**
   * Stops the fake upstream.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @AfterAll
  static void stop() {
    UPSTREAM.close();
  }

  /**
   * A fresh agent for every test, so no counter carries over.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @BeforeEach
  void setUp() {
    String key = Agents.agentKey(principals, Map.of("team", "support"));
    agent =
        new McpTestClient(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .withHeader("Authorization", "Bearer " + key);
  }

  /**
   * Calls a tool, optionally with an idempotency key.
   *
   * @param tool the tool name on the upstream
   * @param arguments the arguments
   * @param key the idempotency key, or null
   * @return the result
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private JsonNode call(String tool, ObjectNode arguments, String key) {
    ObjectNode params = McpTestClient.modernParams();
    if (key != null) {
      ((ObjectNode) params.get("_meta")).put("aduana/idempotency-key", key);
    }
    params.put("name", SERVER + "__" + tool);
    params.set("arguments", arguments);
    return agent.modern("tools/call", params).body().path("result");
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
   * Builds `{"text": value}`.
   *
   * @param value the text
   * @return the arguments
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static ObjectNode text(String value) {
    return JSON.createObjectNode().put("text", value);
  }

  @Test
  void going_over_the_rate_answers_rate_limited_with_when_to_retry() {
    List<JsonNode> results = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      results.add(call("echo", text("hello " + i), null));
    }
    assertThat(results.subList(0, 3)).allMatch(result -> code(result).isEmpty());
    JsonNode limited =
        results.stream().filter(result -> code(result).equals("RATE_LIMITED")).findFirst().get();
    long retryAfter = limited.path("structuredContent").path("retry_after_ms").asLong(-1);
    assertThat(retryAfter).isBetween(1L, 60_000L);
    AuditFilter filter =
        new AuditFilter(
            0,
            1000,
            Optional.empty(),
            Optional.of(SERVER + "__echo"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    assertThat(audit.entries(Tenants.DEFAULT, filter))
        .anyMatch(entry ->
            "RATE_LIMITED".equals(entry.path("type").asString())
                && "RATE_LIMITED".equals(entry.path("reason_code").asString()));
  }

  @Test
  void the_counters_are_in_the_database_so_a_restarted_service_still_refuses() {
    UUID principal = UUID.randomUUID();
    LimitService before = new LimitService(jdbc, Clock.systemUTC(), properties, transactionManager);
    for (int i = 0; i < 3; i++) {
      before.admit(Tenants.DEFAULT, principal, "lim__echo", 0);
    }
    LimitService afterRestart =
        new LimitService(jdbc, Clock.systemUTC(), properties, transactionManager);
    assertThat(afterRestart.admit(Tenants.DEFAULT, principal, "lim__echo", 0))
        .map(LimitService.Refusal::code)
        .contains("RATE_LIMITED");
  }

  @Test
  void a_principal_that_spends_its_daily_budget_gets_budget_exceeded() {
    CatalogTool costly =
        catalog.tools(Tenants.DEFAULT).stream()
            .filter(tool -> tool.name().equals("costly") && tool.serverName().equals(SERVER))
            .findFirst()
            .orElseThrow();
    catalog.changeTool(
        Tenants.DEFAULT,
        costly.id(),
        new CatalogService.ToolChange(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(4),
            Optional.empty(),
            false),
        Optional.empty());
    assertThat(code(call("costly", text("a"), null))).isEmpty();
    assertThat(code(call("costly", text("b"), null))).isEmpty();
    JsonNode third = call("costly", text("c"), null);
    assertThat(code(third)).isEqualTo("BUDGET_EXCEEDED");
    assertThat(third.path("structuredContent").path("retry_after_ms").asLong()).isPositive();
  }

  @Test
  void a_result_over_the_size_limit_is_not_passed_on() {
    int before = UPSTREAM.toolCallCount();
    JsonNode result = call("echo", text("x".repeat(3000)), null);
    assertThat(UPSTREAM.toolCallCount()).isEqualTo(before + 1);
    assertThat(code(result)).isEqualTo("UPSTREAM_ERROR");
    assertThat(result.toString()).doesNotContain("xxxxxxxxxx");
  }

  @Test
  void the_same_key_and_arguments_run_once_and_replay_the_stored_result() {
    String key = "refund-" + UUID.randomUUID();
    ObjectNode arguments = JSON.createObjectNode().put("to", "XX01").put("amount", 10);
    int before = UPSTREAM.toolCallCount();
    JsonNode first = call("pay", arguments, key);
    JsonNode second = call("pay", arguments, key);
    assertThat(code(first)).isEmpty();
    assertThat(second).isEqualTo(first);
    assertThat(UPSTREAM.toolCallCount()).isEqualTo(before + 1);
  }

  @Test
  void the_same_key_with_other_arguments_is_an_idempotency_conflict() {
    String key = "refund-" + UUID.randomUUID();
    call("pay", JSON.createObjectNode().put("to", "XX01").put("amount", 10), key);
    int before = UPSTREAM.toolCallCount();
    JsonNode other = call("pay", JSON.createObjectNode().put("to", "XX01").put("amount", 11), key);
    assertThat(code(other)).isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(UPSTREAM.toolCallCount()).isEqualTo(before);
  }

  @Test
  void ten_parallel_calls_with_one_key_reach_the_upstream_once() throws Exception {
    String key = "refund-" + UUID.randomUUID();
    ObjectNode arguments = JSON.createObjectNode().put("to", "XX02").put("amount", 20);
    int before = UPSTREAM.toolCallCount();
    List<JsonNode> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
      List<Callable<JsonNode>> calls =
          IntStream.range(0, 10)
              .mapToObj(i -> (Callable<JsonNode>) () -> call("pay", arguments, key))
              .toList();
      for (Future<JsonNode> future : pool.invokeAll(calls)) {
        results.add(future.get());
      }
    }
    assertThat(UPSTREAM.toolCallCount()).isEqualTo(before + 1);
    assertThat(results).allMatch(result -> code(result).isEmpty());
  }

  @Test
  void a_key_that_is_not_a_short_string_is_invalid() {
    ObjectNode params = McpTestClient.modernParams();
    ((ObjectNode) params.get("_meta")).put("aduana/idempotency-key", 42);
    params.put("name", SERVER + "__echo");
    params.set("arguments", text("hi"));
    JsonNode result = agent.modern("tools/call", params).body().path("result");
    assertThat(code(result)).isEqualTo("ARGS_INVALID");
  }
}
