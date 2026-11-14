// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.aduana.gateway.identity.PrincipalService;
import io.github.rnachoc.aduana.gateway.support.Agents;
import io.github.rnachoc.aduana.gateway.support.FakeStdioServer;
import io.github.rnachoc.aduana.gateway.support.McpTestClient;
import io.github.rnachoc.aduana.gateway.support.TestDatabase;
import io.github.rnachoc.aduana.gateway.upstream.UpstreamClient;
import io.github.rnachoc.aduana.gateway.upstream.UpstreamResponse;
import io.github.rnachoc.aduana.gateway.upstream.UpstreamServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
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

/**
 * Phase 8: stdio servers run as allow-listed child processes with a clean environment, and a child
 * that dies or hangs never hangs a call.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "aduana.master-key=local-only",
      "aduana.catalog.sync-interval=PT1H",
      "aduana.policy.directory=src/test/resources/policies/allow-all"
    })
@Import(TestDatabase.class)
class StdioTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String JAVA =
      Path.of(System.getProperty("java.home"), "bin", "java").toString();
  private static final Path WORK = temporaryDirectory();
  private static final Path STARTS = WORK.resolve("starts.log");
  private static final Path MARKER = WORK.resolve("must-not-exist");

  @LocalServerPort private int port;
  @Autowired private PrincipalService principals;
  @Autowired private UpstreamClient upstreams;
  private McpTestClient agent;

  /**
   * Creates the directory the fake server writes its launches to.
   *
   * @return the directory
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("aduana-stdio");
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  /**
   * Builds the `stdio:` endpoint that runs the fake server with this JVM; the paths need no
   * percent-encoding.
   *
   * @return the endpoint
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String fakeEndpoint() {
    try {
      String classes =
          Path.of(FakeStdioServer.class.getProtectionDomain().getCodeSource().getLocation().toURI())
              .toString();
      return "stdio:"
          + JAVA
          + "?arg=-Xmx64m&arg=-cp&arg="
          + classes
          + "&arg="
          + FakeStdioServer.class.getName()
          + "&arg="
          + STARTS.toString();
    } catch (URISyntaxException exception) {
      throw new IllegalStateException(exception);
    }
  }

  /**
   * Registers the fake stdio server, allowed, and a command that is not on the allow-list.
   *
   * @param registry the property registry
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("aduana.stdio.allowed-commands", () -> JAVA);
    registry.add("aduana.upstreams[0].name", () -> "sio");
    registry.add("aduana.upstreams[0].endpoint", StdioTest::fakeEndpoint);
    registry.add("aduana.upstreams[0].timeout", () -> "PT2S");
    registry.add("aduana.upstreams[1].name", () -> "sbad");
    registry.add(
        "aduana.upstreams[1].endpoint", () -> "stdio:/usr/bin/touch?arg=" + MARKER.toString());
  }

  /**
   * A fresh agent for every test.
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
   * Calls a tool of the stdio server.
   *
   * @param tool the tool
   * @param text the `text` argument
   * @return the result
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private JsonNode call(String tool, String text) {
    return agent
        .callTool("sio__" + tool, JSON.createObjectNode().put("text", text))
        .body()
        .path("result");
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
   * Calls `echo` until it succeeds, for at most 40 seconds.
   *
   * @param text the text to echo
   * @return the successful result
   * @throws InterruptedException if interrupted while waiting
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private JsonNode echoUntilAnswered(String text) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
    while (true) {
      JsonNode result = call("echo", text);
      if (code(result).isEmpty() || System.nanoTime() > deadline) {
        return result;
      }
      Thread.sleep(250);
    }
  }

  /**
   * Counts the launches of the fake server so far.
   *
   * @return the number of starts
   * @throws IOException if the log cannot be read
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static long starts() throws IOException {
    return Files.exists(STARTS) ? Files.readAllLines(STARTS).size() : 0;
  }

  @Test
  void a_stdio_server_is_listed_and_called_through_the_gateway() throws Exception {
    JsonNode listed = agent.modern("tools/list", McpTestClient.modernParams()).body();
    assertThat(listed.toString()).contains("sio__echo");
    JsonNode result = echoUntilAnswered("hello");
    assertThat(code(result)).isEmpty();
    assertThat(result.path("content").get(0).path("text").asString()).isEqualTo("hello");
  }

  @Test
  void the_child_gets_a_clean_environment() throws Exception {
    echoUntilAnswered("warm up");
    JsonNode result = call("env", "");
    assertThat(result.path("content").get(0).path("text").asString()).isEqualTo("LANG,PATH");
  }

  @Test
  void a_command_off_the_allow_list_is_never_launched() {
    UpstreamServer bad =
        new UpstreamServer(
            "sbad", URI.create("stdio:/usr/bin/touch?arg=" + MARKER.toString()), null);
    UpstreamResponse response = upstreams.send(bad, "tools/list", JSON.createObjectNode());
    assertThat(response).isEqualTo(new UpstreamResponse.Unavailable("command not allowed"));
    assertThat(MARKER).doesNotExist();
    JsonNode listed = agent.modern("tools/list", McpTestClient.modernParams()).body();
    assertThat(listed.toString()).doesNotContain("sbad__");
  }

  @Test
  void a_child_that_dies_fails_the_call_and_the_next_call_starts_it_again() throws Exception {
    echoUntilAnswered("before");
    long before = starts();
    long started = System.nanoTime();
    JsonNode crashed = call("crash", "");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    assertThat(code(crashed)).isEqualTo("UPSTREAM_ERROR");
    JsonNode after = echoUntilAnswered("after");
    assertThat(code(after)).isEmpty();
    assertThat(starts()).isEqualTo(before + 1);
  }

  @Test
  void a_child_that_hangs_times_out_is_stopped_and_started_again() throws Exception {
    echoUntilAnswered("before");
    long before = starts();
    long started = System.nanoTime();
    JsonNode hung = call("hang", "");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    assertThat(code(hung)).isEqualTo("UPSTREAM_TIMEOUT");
    assertThat(code(echoUntilAnswered("after"))).isEmpty();
    assertThat(starts()).isEqualTo(before + 1);
  }
}
