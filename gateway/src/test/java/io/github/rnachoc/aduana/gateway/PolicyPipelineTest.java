// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.aduana.gateway.events.EventLog;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Step 6 of the pipeline: the active policy decides every call and filters every tool list, and a
 * bundle that fails its checks never replaces the active one (CA4.3).
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aduana.master-key=local-only",
      "aduana.catalog.sync-interval=PT1H",
      "aduana.policy.directory=src/test/resources/policies/pipeline"
    })
@Import(TestDatabase.class)
class PolicyPipelineTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ADMIN = "policy-admin-" + UUID.randomUUID() + "@example.com";
  private static final String PASSWORD = "correct horse battery";
  private static final FakeUpstream SHOP = start();

  @LocalServerPort private int port;
  @Autowired private PrincipalService principals;
  @Autowired private EventLog events;
  @Autowired private ConsoleUserService users;
  private McpTestClient support;
  private McpTestClient stranger;
  private ApiTestClient admin;

  /**
   * Starts the fake shop with four tools.
   *
   * @return the upstream
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static FakeUpstream start() {
    try {
      FakeUpstream shop =
          new FakeUpstream()
              .withEchoTool("read", "Read.", "id")
              .withEchoTool("hidden", "Never offered.", "id")
              .withEchoTool("other", "No rule names it.", "id");
      return shop.withEchoTool("pay", "Pay.", "id", "amount:integer");
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Registers the shop as the only upstream and the administrator.
   *
   * @param registry the property registry
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("aduana.upstreams[0].name", () -> "shop");
    registry.add("aduana.upstreams[0].endpoint", SHOP::url);
    registry.add("aduana.bootstrap.admin-email", () -> ADMIN);
    registry.add("aduana.bootstrap.admin-password", () -> PASSWORD);
  }

  /**
   * Stops the fake shop.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @AfterAll
  static void stop() {
    SHOP.close();
  }

  /**
   * Opens a support agent, an agent with no team, and an administrator session.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @BeforeEach
  void setUp() {
    URI mcp = URI.create("http://127.0.0.1:" + port + "/mcp");
    support =
        new McpTestClient(mcp)
            .withHeader(
                "Authorization",
                "Bearer " + Agents.agentKey(principals, Map.of("team", "support")));
    stranger =
        new McpTestClient(mcp)
            .withHeader("Authorization", "Bearer " + Agents.agentKey(principals, Map.of()));
    admin = new ApiTestClient(URI.create("http://127.0.0.1:" + port));
    if (admin.login(ADMIN, PASSWORD).status() != 200) {
      users.create(Tenants.DEFAULT, ADMIN, PASSWORD, ConsoleUser.Role.ADMIN);
      assertThat(admin.login(ADMIN, PASSWORD).status()).isEqualTo(200);
    }
  }

  /**
   * Calls a shop tool with an id and an optional amount.
   *
   * @param client the agent
   * @param tool the tool
   * @param amount the amount, or null for none
   * @return the result object
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static JsonNode call(McpTestClient client, String tool, Integer amount) {
    ObjectNode arguments = JSON.createObjectNode().put("id", "1");
    if (amount != null) {
      arguments.put("amount", amount);
    }
    return client.callTool("shop__" + tool, arguments).body().path("result");
  }

  /**
   * Lists the tool names an agent sees.
   *
   * @param client the agent
   * @return the names
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static List<String> listed(McpTestClient client) {
    return client
        .modern("tools/list", McpTestClient.modernParams())
        .body()
        .path("result")
        .path("tools")
        .valueStream()
        .map(tool -> tool.path("name").asString())
        .toList();
  }

  /**
   * Reads a policy file shipped with the tests.
   *
   * @param name the file under the pipeline bundle
   * @return its text
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String file(String name) {
    try {
      return Files.readString(
          Path.of("src/test/resources/policies/pipeline", name), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Builds the JSON body of a policy upload.
   *
   * @param policy the policy text
   * @param tests the test texts
   * @return the body
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String upload(String policy, Map<String, String> tests) {
    ObjectNode body = JSON.createObjectNode().put("policy", policy);
    ObjectNode files = body.putObject("tests");
    tests.forEach(files::put);
    return body.toString();
  }

  @Test
  void an_allowed_call_is_forwarded() {
    int before = SHOP.toolCallCount();
    assertThat(call(support, "read", null).path("isError").asBoolean()).isFalse();
    assertThat(SHOP.toolCallCount()).isEqualTo(before + 1);
  }

  @Test
  void a_denied_call_is_not_forwarded_and_the_hint_reveals_no_rule() {
    int before = SHOP.toolCallCount();
    JsonNode result = call(support, "pay", 500);
    assertThat(result.path("structuredContent").path("code").asString()).isEqualTo("POLICY_DENIED");
    assertThat(result.toString())
        .doesNotContain("secret-payment-limit")
        .doesNotContain("PAYMENT_OVER_LIMIT")
        .doesNotContain("100");
    assertThat(SHOP.toolCallCount()).isEqualTo(before);
  }

  @Test
  void a_call_that_needs_approval_is_held_and_not_forwarded() {
    int before = SHOP.toolCallCount();
    assertThat(call(support, "pay", 50).path("structuredContent").path("code").asString())
        .isEqualTo("APPROVAL_PENDING");
    assertThat(SHOP.toolCallCount()).isEqualTo(before);
  }

  @Test
  void a_call_no_rule_allows_is_denied() {
    assertThat(call(stranger, "read", null).path("structuredContent").path("code").asString())
        .isEqualTo("POLICY_DENIED");
  }

  @Test
  void the_tool_list_shows_only_what_the_policy_can_let_through() {
    assertThat(listed(support)).containsExactly("shop__pay", "shop__read");
    assertThat(listed(stranger)).isEmpty();
  }

  @Test
  void an_invalid_or_failing_bundle_never_replaces_the_active_one() {
    String activeBefore =
        admin.send("GET", "/api/v1/policy", null).body().path("sha256").asString();
    ApiTestClient.Reply invalid =
        admin.send(
            "POST",
            "/api/v1/policy",
            upload("version: 1\ndefaults: {decision: allow}\nrules: []\n", Map.of()));
    assertThat(invalid.status()).isEqualTo(400);
    assertThat(invalid.body().path("code").asString()).isEqualTo("POLICY_REJECTED");
    String everythingAllowed =
        "version: 1\ndefaults: {decision: deny}\nrules:\n  - {id: all, match: {}, effect: allow}\n";
    String failingTest =
        "- name: reading is denied\n  given: { tool: { server: shop, name: read } }\n  expect: { decision: deny }\n";
    assertThat(
            admin
                .send(
                    "POST",
                    "/api/v1/policy",
                    upload(everythingAllowed, Map.of("t.yaml", failingTest)))
                .status())
        .isEqualTo(400);
    String wrongType =
        "version: 1\ndefaults: {decision: deny}\nrules:\n  - id: bad\n    match:\n      tool: { server: shop, name: read }\n"
            + "      args: [ { path: \"$.id\", gt: 3 } ]\n    effect: deny\n";
    ApiTestClient.Reply typeError =
        admin.send("POST", "/api/v1/policy", upload(wrongType, Map.of()));
    assertThat(typeError.status()).isEqualTo(400);
    assertThat(typeError.body().path("errors").toString())
        .contains("cannot apply to a string field");
    assertThat(admin.send("GET", "/api/v1/policy", null).body().path("sha256").asString())
        .isEqualTo(activeBefore);
    assertThat(call(stranger, "read", null).path("structuredContent").path("code").asString())
        .isEqualTo("POLICY_DENIED");
    assertThat(events.recentTypes(Tenants.DEFAULT, 3)).containsOnly("POLICY_REJECTED");
  }

  @Test
  void a_valid_bundle_takes_effect_at_once_and_is_recorded() {
    String everythingAllowed =
        "version: 1\ndefaults: {decision: deny}\nrules:\n  - {id: all, match: {}, effect: allow}\n";
    ApiTestClient.Reply loaded =
        admin.send("POST", "/api/v1/policy", upload(everythingAllowed, Map.of()));
    assertThat(loaded.status()).isEqualTo(201);
    assertThat(call(stranger, "other", null).path("isError").asBoolean()).isFalse();
    assertThat(events.recentTypes(Tenants.DEFAULT, 1)).containsExactly("POLICY_LOADED");
    ApiTestClient.Reply restored =
        admin.send(
            "POST",
            "/api/v1/policy",
            upload(file("policy.yaml"), Map.of("cases.yaml", file("tests/cases.yaml"))));
    assertThat(restored.status()).isEqualTo(201);
    assertThat(admin.send("GET", "/api/v1/policy", null).body().path("results").size())
        .isEqualTo(1);
  }
}
