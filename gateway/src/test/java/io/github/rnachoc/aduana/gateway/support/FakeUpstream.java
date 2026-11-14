// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A scriptable MCP 2026-07-28 server for tests: answers discover, list and call, can be slow or
 * stream its answer as SSE, and records every request it receives.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class FakeUpstream implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpServer server;
  private final Map<String, ObjectNode> tools = new ConcurrentHashMap<>();
  private final List<JsonNode> calls = new CopyOnWriteArrayList<>();
  private final List<Map<String, List<String>>> headers = new CopyOnWriteArrayList<>();
  private final AtomicInteger toolCalls = new AtomicInteger();
  private volatile Duration delay = Duration.ZERO;
  private volatile boolean streamAsSse;

  /**
   * Starts the server on a free loopback port.
   *
   * @throws IOException if the port cannot be bound
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public FakeUpstream() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/mcp", this::handle);
    server.start();
  }

  /**
   * Returns a loopback URL where nothing listens, to play an upstream that is down.
   *
   * @return an MCP URL on a port that was free a moment ago
   * @throws IOException if no port can be probed
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static String unreachableUrl() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return "http://127.0.0.1:" + socket.getLocalPort() + "/mcp";
    }
  }

  /**
   * Returns the MCP endpoint of this server.
   *
   * @return the `/mcp` URL
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
  }

  /**
   * Adds (or replaces) a tool that echoes its arguments back as structured content.
   *
   * @param name the tool name
   * @param description the description published in tools/list
   * @param properties the properties its input schema declares, as `name` (a string) or `name:type`
   * @return this server, for chaining
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public FakeUpstream withEchoTool(String name, String description, String... properties) {
    ObjectNode tool = JSON.createObjectNode();
    tool.put("name", name);
    tool.put("description", description);
    ObjectNode schema = tool.putObject("inputSchema");
    schema.put("type", "object");
    ObjectNode declared = schema.putObject("properties");
    for (String property : properties) {
      String[] parts = property.split(":", 2);
      declared.putObject(parts[0]).put("type", parts.length == 2 ? parts[1] : "string");
    }
    tools.put(name, tool);
    return this;
  }

  /**
   * Removes a tool from the published list.
   *
   * @param name the tool name
   * @return this server, for chaining
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public FakeUpstream withoutTool(String name) {
    tools.remove(name);
    return this;
  }

  /**
   * Makes every tools/call wait before answering.
   *
   * @param value how long to wait
   * @return this server, for chaining
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public FakeUpstream withDelay(Duration value) {
    this.delay = value;
    return this;
  }

  /**
   * Makes the server answer as a `text/event-stream` instead of a JSON body.
   *
   * @return this server, for chaining
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public FakeUpstream withSseResponses() {
    this.streamAsSse = true;
    return this;
  }

  /**
   * Returns how many tools/call requests reached this server.
   *
   * @return the count since start
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public int toolCallCount() {
    return toolCalls.get();
  }

  /**
   * Returns the parsed JSON-RPC requests received so far.
   *
   * @return every request, in arrival order
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<JsonNode> requests() {
    return List.copyOf(calls);
  }

  /**
   * Returns the HTTP headers of every request received so far.
   *
   * @return one header map per request, in arrival order
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<Map<String, List<String>>> requestHeaders() {
    return List.copyOf(headers);
  }

  /**
   * Answers one HTTP exchange.
   *
   * @param exchange the request and its response
   * @throws IOException if the client went away
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private void handle(HttpExchange exchange) throws IOException {
    JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
    calls.add(request);
    headers.add(Map.copyOf(exchange.getRequestHeaders()));
    String method = request.path("method").asString();
    ObjectNode response = JSON.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", request.get("id"));
    switch (method) {
      case "server/discover" -> response.set("result", discover());
      case "tools/list" -> response.set("result", listTools());
      case "tools/call" -> {
        toolCalls.incrementAndGet();
        pause();
        callTool(request.path("params"), response);
      }
      default -> {
        ObjectNode error = response.putObject("error");
        error.put("code", -32601);
        error.put("message", "Method not found");
      }
    }
    send(exchange, response);
  }

  /**
   * Builds the server/discover result.
   *
   * @return the result object
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ObjectNode discover() {
    ObjectNode result = JSON.createObjectNode();
    result.put("resultType", "complete");
    result.putArray("supportedVersions").add("2026-07-28");
    result.putObject("capabilities").putObject("tools");
    return result;
  }

  /**
   * Builds the tools/list result from the registered tools, sorted by name.
   *
   * @return the result object
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ObjectNode listTools() {
    ObjectNode result = JSON.createObjectNode();
    result.put("resultType", "complete");
    ArrayNode list = result.putArray("tools");
    tools.keySet().stream().sorted().forEach(name -> list.add(tools.get(name)));
    result.put("ttlMs", 60000);
    result.put("cacheScope", "public");
    return result;
  }

  /**
   * Answers a tools/call: echoes the arguments, or reports an unknown tool as -32602.
   *
   * @param params the call parameters
   * @param response the response to fill
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private void callTool(JsonNode params, ObjectNode response) {
    String name = params.path("name").asString();
    if (!tools.containsKey(name)) {
      ObjectNode error = response.putObject("error");
      error.put("code", -32602);
      error.put("message", "Unknown tool: " + name);
      return;
    }
    ObjectNode result = response.putObject("result");
    result.put("resultType", "complete");
    JsonNode arguments = params.path("arguments");
    result.putArray("content").addObject().put("type", "text").put("text", arguments.toString());
    result.set("structuredContent", arguments);
    result.put("isError", false);
  }

  /**
   * Waits for the configured delay, keeping the interrupt flag if interrupted.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private void pause() {
    if (delay.isZero()) {
      return;
    }
    try {
      Thread.sleep(delay);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Writes the response as JSON or as a one-event SSE stream.
   *
   * @param exchange the exchange to answer
   * @param response the JSON-RPC response
   * @throws IOException if the client went away
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private void send(HttpExchange exchange, ObjectNode response) throws IOException {
    String json = JSON.writeValueAsString(response);
    String body = streamAsSse ? "event: message\ndata: " + json + "\n\n" : json;
    exchange
        .getResponseHeaders()
        .set("content-type", streamAsSse ? "text/event-stream" : "application/json");
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  /**
   * Stops the server immediately.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Override
  public void close() {
    server.stop(0);
  }
}
