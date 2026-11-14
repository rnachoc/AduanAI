// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Sends raw JSON-RPC to the gateway the way a 2026-07-28 client does, so tests see the wire format.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class McpTestClient {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient http = HttpClient.newHttpClient();
  private final URI endpoint;
  private final Map<String, String> extraHeaders = new LinkedHashMap<>();
  private int nextId = 1;

  /**
   * The HTTP status and parsed body of one exchange.
   *
   * @param status the HTTP status code
   * @param body the parsed JSON body, or an empty object
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Reply(int status, JsonNode body) {}

  /**
   * Targets the MCP endpoint of a gateway.
   *
   * @param endpoint the gateway's `/mcp` URL
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public McpTestClient(URI endpoint) {
    this.endpoint = endpoint;
  }

  /**
   * Adds a header to every later request, such as an Authorization header.
   *
   * @param name the header name
   * @param value the header value
   * @return this client, for chaining
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public McpTestClient withHeader(String name, String value) {
    extraHeaders.put(name, value);
    return this;
  }

  /**
   * Builds the parameters object with the `_meta` keys 2026-07-28 requires.
   *
   * @return a parameters object ready for more fields
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static ObjectNode modernParams() {
    ObjectNode params = JSON.createObjectNode();
    ObjectNode meta = params.putObject("_meta");
    meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
    meta.putObject("io.modelcontextprotocol/clientCapabilities");
    meta.putObject("io.modelcontextprotocol/clientInfo").put("name", "test").put("version", "1");
    return params;
  }

  /**
   * Sends a modern request with the matching protocol headers.
   *
   * @param method the JSON-RPC method
   * @param params the parameters, normally from {@link #modernParams()}
   * @return the reply
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Reply modern(String method, ObjectNode params) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("MCP-Protocol-Version", "2026-07-28");
    headers.put("Mcp-Method", method);
    if (params.has("name")) {
      headers.put("Mcp-Name", params.get("name").asString());
    }
    return send(method, params, headers);
  }

  /**
   * Calls a tool with the modern envelope.
   *
   * @param name the namespaced tool name
   * @param arguments the tool arguments
   * @return the reply
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Reply callTool(String name, ObjectNode arguments) {
    ObjectNode params = modernParams();
    params.put("name", name);
    params.set("arguments", arguments);
    return modern("tools/call", params);
  }

  /**
   * Sends a request with arbitrary headers and no `_meta` added.
   *
   * @param method the JSON-RPC method
   * @param params the parameters
   * @param headers the HTTP headers to send besides content type and accept
   * @return the reply
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Reply send(String method, ObjectNode params, Map<String, String> headers) {
    ObjectNode envelope = JSON.createObjectNode();
    envelope.put("jsonrpc", "2.0");
    envelope.put("id", nextId++);
    envelope.put("method", method);
    envelope.set("params", params);
    return post(JSON.writeValueAsString(envelope), headers);
  }

  /**
   * Posts a raw body and parses the JSON answer, if any.
   *
   * @param body the request body
   * @param headers the HTTP headers to send besides content type and accept
   * @return the reply; the body is an empty object when the response had none
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Reply post(String body, Map<String, String> headers) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(endpoint)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    extraHeaders.forEach(builder::header);
    headers.forEach(builder::header);
    return exchange(builder.build());
  }

  /**
   * Sends a request with another HTTP method, such as GET.
   *
   * @param method the HTTP method
   * @return the reply
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Reply other(String method) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(endpoint).method(method, HttpRequest.BodyPublishers.noBody());
    extraHeaders.forEach(builder::header);
    return exchange(builder.build());
  }

  /**
   * Runs one HTTP exchange.
   *
   * @param request the request
   * @return the reply
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Reply exchange(HttpRequest request) {
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      String text = response.body();
      JsonNode body = text.isBlank() ? JSON.createObjectNode() : JSON.readTree(text);
      return new Reply(response.statusCode(), body);
    } catch (IOException exception) {
      throw new IllegalStateException("request to the gateway failed", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", exception);
    }
  }
}
