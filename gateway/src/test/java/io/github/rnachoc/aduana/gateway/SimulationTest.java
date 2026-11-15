// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * Phase 10: a candidate policy replayed over logged decisions, with decisions that depend on
 * redacted arguments reported apart.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aduana.master-key=local-only",
      "aduana.catalog.sync-interval=PT1H",
      "aduana.approvals.hold-timeout=PT1S",
      "aduana.policy.directory=src/test/resources/policies/simulation"
    })
@Import(TestDatabase.class)
class SimulationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ADMIN = "sim-admin-" + UUID.randomUUID() + "@example.com";
  private static final String PASSWORD = "correct horse battery";
  private static final Path POLICY = Path.of("src/test/resources/policies/simulation/policy.yaml");
  private static final FakeUpstream SHOP = start();

  @LocalServerPort private int port;
  @Autowired private PrincipalService principals;
  @Autowired private ConsoleUserService users;
  private McpTestClient agent;
  private ApiTestClient admin;

  /**
   * Starts the fake shop.
   *
   * @return the upstream
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static FakeUpstream start() {
    try {
      return new FakeUpstream()
          .withEchoTool("note", "Write a note.", "text")
          .withEchoTool("pay", "Pay.", "amount:integer");
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Registers the shop.
   *
   * @param registry the property registry
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("aduana.upstreams[0].name", () -> "simshop");
    registry.add("aduana.upstreams[0].endpoint", SHOP::url);
  }

  /**
   * Stops the shop.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @AfterAll
  static void stop() {
    SHOP.close();
  }

  /**
   * A support agent and a signed-in administrator.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @BeforeEach
  void setUp() {
    agent =
        new McpTestClient(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .withHeader(
                "Authorization",
                "Bearer " + Agents.agentKey(principals, Map.of("team", "support")));
    admin = new ApiTestClient(URI.create("http://127.0.0.1:" + port));
    if (admin.login(ADMIN, PASSWORD).status() != 200) {
      users.create(Tenants.DEFAULT, ADMIN, PASSWORD, ConsoleUser.Role.ADMIN);
      assertThat(admin.login(ADMIN, PASSWORD).status()).isEqualTo(200);
    }
  }

  /**
   * Makes six calls with known decisions and returns the first seq they could have.
   *
   * @return the seq to simulate from
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private long traffic() {
    long from = admin.send("GET", "/api/v1/audit/verify", null).body().path("lastSeq").asLong() + 1;
    note("ok fine");
    note("nope");
    note("ok card 4111 1111 1111 1111");
    note("ok mail ana@example.com");
    agent.callTool("simshop__pay", JSON.createObjectNode().put("amount", 50));
    agent.callTool("simshop__pay", JSON.createObjectNode().put("amount", 500));
    return from;
  }

  /**
   * Calls `simshop__note`.
   *
   * @param text the note
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private void note(String text) {
    agent.callTool("simshop__note", JSON.createObjectNode().put("text", text));
  }

  /**
   * Asks the gateway to simulate a policy.
   *
   * @param policy the candidate policy text
   * @param from the first seq
   * @return the HTTP reply
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ApiTestClient.Reply simulate(String policy, long from) {
    ObjectNode body = JSON.createObjectNode().put("policy", policy).put("fromSeq", from);
    body.putObject("tests");
    return admin.send("POST", "/api/v1/policy/simulate", body.toString());
  }

  /**
   * Lists the seqs of a result section.
   *
   * @param nodes an array of objects with `seq`
   * @return the seqs
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static List<Long> seqs(JsonNode nodes) {
    List<Long> seqs = new ArrayList<>();
    nodes.forEach(node -> seqs.add(node.path("seq").asLong()));
    return seqs;
  }

  @Test
  void the_active_policy_against_its_own_traffic_changes_nothing() throws IOException {
    long from = traffic();
    ApiTestClient.Reply reply = simulate(Files.readString(POLICY), from);
    assertThat(reply.status()).isEqualTo(200);
    JsonNode result = reply.body();
    assertThat(result.path("decisions").asInt()).isEqualTo(6);
    assertThat(result.path("changed").asInt()).isZero();
    assertThat(result.path("unchanged").asInt() + result.path("unknown").asInt()).isEqualTo(6);
  }

  @Test
  void conditions_on_redacted_arguments_are_unknown_never_unchanged() throws IOException {
    long from = traffic();
    JsonNode result = simulate(Files.readString(POLICY), from).body();
    assertThat(result.path("unknown").asInt()).isEqualTo(1);
    assertThat(result.path("unknowns").toString()).contains("simshop__note").contains("$.text");
    assertThat(result.path("unchanged").asInt()).isEqualTo(5);
  }

  @Test
  void a_redacted_value_that_cannot_change_the_outcome_does_not_make_it_unknown()
      throws IOException {
    long from = traffic();
    String candidate =
        Files.readString(POLICY)
            + """
              - id: notes-frozen
                match:
                  tool: { server: simshop, name: note }
                effect: deny
                reason_code: NOTES_FROZEN
            """;
    JsonNode result = simulate(candidate, from).body();
    assertThat(result.path("unknown").asInt()).isZero();
    assertThat(result.path("changed").asInt()).isEqualTo(2);
    assertThat(result.path("changes").toString()).contains("notes-frozen");
  }

  @Test
  void removing_a_rule_shows_the_decisions_that_change_grouped_by_rule() throws IOException {
    long from = traffic();
    String candidate =
        Files.readString(POLICY)
            .replace(
                """
                  - id: big-payments-need-approval
                    match:
                      tool: { server: simshop, name: pay }
                      args: [ { path: '$.amount', gt: 100 } ]
                    effect: require_approval
                """,
                "");
    JsonNode result = simulate(candidate, from).body();
    assertThat(result.path("changed").asInt()).isEqualTo(1);
    JsonNode group = result.path("changes").get(0);
    assertThat(group.path("before").asString()).isEqualTo("require_approval");
    assertThat(group.path("after").asString()).isEqualTo("allow");
    assertThat(group.path("rules").asString()).isEqualTo("small-payments");
    assertThat(group.path("count").asInt()).isEqualTo(1);
    assertThat(seqs(group.path("examples"))).hasSize(1);
  }

  @Test
  void an_invalid_candidate_is_refused_with_its_errors() {
    ApiTestClient.Reply reply = simulate("version: 1\nrules: [ { id: x } ]", 1);
    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.body().path("errors").size()).isPositive();
  }
}
