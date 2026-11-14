// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.aduana.gateway.upstream.UpstreamClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Drives the gateway's MCP endpoint against fake upstreams: forwarding, namespacing, timeouts,
 * upstream failures, size limits and the protocol envelope.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"aduana.limits.max-arguments-bytes=1024"})
class ProxyEndpointTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FakeUpstream CRM = start().withEchoTool("get_customer", "Get a customer.");
  private static final FakeUpstream PAYMENTS =
      start().withEchoTool("refund", "Refund an order.").withSseResponses();
  private static final FakeUpstream SLOW =
      start().withEchoTool("wait", "Takes long.").withDelay(Duration.ofSeconds(5));

  @LocalServerPort private int port;
  @Autowired private UpstreamClient upstreamClient;
  private McpTestClient client;

  /**
   * Starts a fake upstream, turning the checked exception into an unchecked one for field use.
   *
   * @return a running fake upstream
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static FakeUpstream start() {
    try {
      return new FakeUpstream();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Points the gateway at the fake upstreams and at one address where nothing listens.
   *
   * @param registry the Spring test property registry
   * @throws IOException if no free port can be probed
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @DynamicPropertySource
  static void upstreams(DynamicPropertyRegistry registry) throws IOException {
    String down = FakeUpstream.unreachableUrl();
    List<Map.Entry<String, String>> servers =
        List.of(
            Map.entry("crm", CRM.url()),
            Map.entry("payments", PAYMENTS.url()),
            Map.entry("slow", SLOW.url()),
            Map.entry("down", down));
    for (int index = 0; index < servers.size(); index++) {
      String prefix = "aduana.upstreams[" + index + "].";
      registry.add(prefix + "name", servers.get(index)::getKey);
      registry.add(prefix + "endpoint", servers.get(index)::getValue);
      registry.add(prefix + "timeout", () -> "400ms");
    }
  }

  /**
   * Stops the fake upstreams after the class.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @AfterAll
  static void stopUpstreams() {
    CRM.close();
    PAYMENTS.close();
    SLOW.close();
  }

  /**
   * Opens a test client on the gateway before each test.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @BeforeEach
  void connect() {
    client = new McpTestClient(URI.create("http://127.0.0.1:" + port + "/mcp"));
  }

  /**
   * Builds an arguments object from alternating keys and values.
   *
   * @param pairs key, value, key, value…
   * @return the arguments object
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static ObjectNode arguments(String... pairs) {
    ObjectNode node = JSON.createObjectNode();
    for (int index = 0; index < pairs.length; index += 2) {
      node.put(pairs[index], pairs[index + 1]);
    }
    return node;
  }

  @Test
  void discover_answers_with_the_2026_07_28_revision_and_the_tools_capability() {
    JsonNode result = client.modern("server/discover", McpTestClient.modernParams()).body();
    assertThat(result.path("result").path("supportedVersions").toString()).contains("2026-07-28");
    assertThat(result.path("result").path("capabilities").has("tools")).isTrue();
    assertThat(result.path("result").path("resultType").asString()).isEqualTo("complete");
  }

  @Test
  void the_tool_list_merges_every_upstream_under_server_prefixed_names() {
    JsonNode result = client.modern("tools/list", McpTestClient.modernParams()).body();
    List<String> names =
        result
            .path("result")
            .path("tools")
            .valueStream()
            .map(t -> t.path("name").asString())
            .toList();
    assertThat(names).contains("crm__get_customer", "payments__refund", "slow__wait");
    assertThat(result.path("result").has("ttlMs")).isTrue();
    assertThat(result.path("result").has("cacheScope")).isTrue();
  }

  @Test
  void a_call_is_forwarded_under_its_upstream_name_and_its_result_comes_back_unchanged() {
    JsonNode reply =
        client.callTool("crm__get_customer", arguments("customer_id", "C-1001")).body();
    assertThat(reply.path("result").path("structuredContent").path("customer_id").asString())
        .isEqualTo("C-1001");
    JsonNode forwarded = CRM.requests().getLast();
    assertThat(forwarded.path("params").path("name").asString()).isEqualTo("get_customer");
    assertThat(CRM.requestHeaders().getLast()).containsKey("Mcp-name");
  }

  @Test
  void an_upstream_that_answers_with_sse_is_read_like_a_json_answer() {
    JsonNode reply = client.callTool("payments__refund", arguments("order_id", "O-1")).body();
    assertThat(reply.path("result").path("structuredContent").path("order_id").asString())
        .isEqualTo("O-1");
  }

  @Test
  void a_tool_on_an_unknown_server_is_tool_not_found() {
    JsonNode reply = client.callTool("nowhere__anything", arguments()).body();
    assertThat(reply.path("error").path("code").asInt()).isEqualTo(-32602);
    assertThat(reply.path("error").path("data").path("code").asString())
        .isEqualTo("TOOL_NOT_FOUND");
  }

  @Test
  void an_unknown_tool_on_a_known_server_gives_the_same_tool_not_found() {
    JsonNode unknownServer = client.callTool("nowhere__get_customer", arguments()).body();
    JsonNode unknownTool = client.callTool("crm__drop_tables", arguments()).body();
    assertThat(unknownTool.path("error").path("data").path("code").asString())
        .isEqualTo("TOOL_NOT_FOUND");
    assertThat(unknownTool.path("error").path("message"))
        .isEqualTo(unknownServer.path("error").path("message"));
  }

  @Test
  void an_upstream_that_is_down_gives_upstream_error() {
    JsonNode reply = client.callTool("down__anything", arguments()).body();
    assertThat(reply.path("result").path("isError").asBoolean()).isTrue();
    assertThat(reply.path("result").path("structuredContent").path("code").asString())
        .isEqualTo("UPSTREAM_ERROR");
  }

  @Test
  void a_slow_upstream_gives_upstream_timeout_and_leaves_nothing_in_flight() throws Exception {
    long started = System.nanoTime();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Callable<JsonNode>> calls =
          IntStream.range(0, 10)
              .mapToObj(
                  i -> (Callable<JsonNode>) () -> client.callTool("slow__wait", arguments()).body())
              .toList();
      for (Future<JsonNode> future : pool.invokeAll(calls)) {
        assertThat(future.get().path("result").path("structuredContent").path("code").asString())
            .isEqualTo("UPSTREAM_TIMEOUT");
      }
    }
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    assertThat(upstreamClient.inFlightRequests()).isZero();
  }

  @Test
  void oversized_arguments_are_refused_before_anything_is_forwarded() {
    int before = CRM.toolCallCount();
    JsonNode reply =
        client.callTool("crm__get_customer", arguments("blob", "x".repeat(2000))).body();
    assertThat(reply.path("result").path("structuredContent").path("code").asString())
        .isEqualTo("ARGS_INVALID");
    assertThat(CRM.toolCallCount()).isEqualTo(before);
  }

  @Test
  void a_protocol_header_that_contradicts_meta_is_rejected_with_400() {
    ObjectNode params = McpTestClient.modernParams();
    McpTestClient.Reply reply =
        client.send(
            "tools/list",
            params,
            Map.of("MCP-Protocol-Version", "2025-11-25", "Mcp-Method", "tools/list"));
    assertThat(reply.status()).isEqualTo(400);
    assertThat(reply.body().path("error").path("code").asInt()).isEqualTo(-32020);
  }

  @Test
  void get_on_the_endpoint_is_405() {
    assertThat(client.other("GET").status()).isEqualTo(405);
  }

  @Test
  void an_unsupported_method_is_method_not_found() {
    McpTestClient.Reply reply =
        client.modern("sampling/createMessage", McpTestClient.modernParams());
    assertThat(reply.body().path("error").path("code").asInt()).isEqualTo(-32601);
  }

  @Test
  void a_notification_is_accepted_with_202_and_no_body() {
    McpTestClient.Reply reply =
        client.post(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
            Map.of("MCP-Protocol-Version", "2025-11-25"));
    assertThat(reply.status()).isEqualTo(202);
  }

  @Test
  void resources_and_prompts_are_listed_empty() {
    JsonNode resources = client.modern("resources/list", McpTestClient.modernParams()).body();
    JsonNode prompts = client.modern("prompts/list", McpTestClient.modernParams()).body();
    assertThat(resources.path("result").path("resources").size()).isZero();
    assertThat(prompts.path("result").path("prompts").size()).isZero();
  }

  @Test
  void a_2025_11_25_client_can_initialize_list_and_call() {
    ObjectNode init = JSON.createObjectNode();
    init.put("protocolVersion", "2025-11-25");
    init.putObject("capabilities");
    init.putObject("clientInfo").put("name", "old").put("version", "1");
    JsonNode initialized = client.send("initialize", init, Map.of()).body();
    assertThat(initialized.path("result").path("protocolVersion").asString())
        .isEqualTo("2025-11-25");
    assertThat(initialized.path("result").path("capabilities").has("tools")).isTrue();

    Map<String, String> legacy = Map.of("MCP-Protocol-Version", "2025-11-25");
    JsonNode list = client.send("tools/list", JSON.createObjectNode(), legacy).body();
    assertThat(list.path("result").path("tools").size()).isGreaterThanOrEqualTo(3);

    ObjectNode call = JSON.createObjectNode();
    call.put("name", "crm__get_customer");
    call.set("arguments", arguments("customer_id", "C-1002"));
    JsonNode result = client.send("tools/call", call, legacy).body();
    assertThat(result.path("result").path("structuredContent").path("customer_id").asString())
        .isEqualTo("C-1002");
  }
}
