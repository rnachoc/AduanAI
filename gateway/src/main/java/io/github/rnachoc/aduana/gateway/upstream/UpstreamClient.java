// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.upstream;

import io.github.rnachoc.aduana.gateway.catalog.Secret;
import io.github.rnachoc.aduana.gateway.mcp.McpProtocol;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Speaks MCP 2026-07-28 to upstream servers as a client: one POST per request, JSON or SSE back.
 *
 * <p>Every request carries the gateway's own `_meta` and protocol headers; nothing from the agent's
 * HTTP request (headers, credentials) is copied, so an agent token never reaches an upstream.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class UpstreamClient {

  private static final Logger LOG = LoggerFactory.getLogger(UpstreamClient.class);
  private static final int MAX_PAGES = 50;

  private final HttpClient http;
  private final JsonMapper json;
  private final AtomicLong nextId = new AtomicLong(1);
  private final AtomicInteger inFlight = new AtomicInteger();

  /**
   * Creates the client.
   *
   * @param http the shared HTTP client
   * @param json the shared JSON mapper
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public UpstreamClient(HttpClient http, JsonMapper json) {
    this.http = http;
    this.json = json;
  }

  /**
   * Sends one JSON-RPC request and waits at most the server's timeout for the answer.
   *
   * <p>On timeout the HTTP exchange is cancelled, so no request outlives its caller.
   *
   * @param server the upstream
   * @param method the JSON-RPC method
   * @param params the parameters, without `_meta`; the gateway's `_meta` is added
   * @return the upstream's answer, or why there is none
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public UpstreamResponse send(UpstreamServer server, String method, ObjectNode params) {
    return send(server, method, params, Optional.empty());
  }

  /**
   * Sends one JSON-RPC request with the server's credential injected as a bearer token.
   *
   * <p>The credential comes from the catalog, never from the agent (principle 3).
   *
   * @param server the upstream
   * @param method the JSON-RPC method
   * @param params the parameters, without `_meta`
   * @param credential the server's credential, if it has one
   * @return the upstream's answer, or why there is none
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public UpstreamResponse send(
      UpstreamServer server, String method, ObjectNode params, Optional<Secret> credential) {
    long id = nextId.getAndIncrement();
    HttpRequest request = buildRequest(server, method, params, id, credential);
    inFlight.incrementAndGet();
    CompletableFuture<HttpResponse<byte[]>> future =
        http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
    try {
      HttpResponse<byte[]> response =
          future.get(server.timeout().toMillis(), TimeUnit.MILLISECONDS);
      return interpret(response, id);
    } catch (TimeoutException exception) {
      return new UpstreamResponse.TimedOut();
    } catch (ExecutionException exception) {
      if (exception.getCause() instanceof HttpTimeoutException) {
        return new UpstreamResponse.TimedOut();
      }
      return new UpstreamResponse.Unavailable(describe(exception.getCause()));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return new UpstreamResponse.Unavailable("interrupted");
    } finally {
      future.cancel(true);
      inFlight.decrementAndGet();
    }
  }

  /**
   * Reads every page of a server's tool list.
   *
   * @param server the upstream
   * @param credential the server's credential, if any
   * @return the published tools, or empty when the server did not answer with a list
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<List<JsonNode>> listTools(UpstreamServer server, Optional<Secret> credential) {
    List<JsonNode> tools = new ArrayList<>();
    ObjectNode params = json.createObjectNode();
    for (int page = 0; page < MAX_PAGES; page++) {
      UpstreamResponse response = send(server, "tools/list", params, credential);
      if (!(response instanceof UpstreamResponse.Result(JsonNode result))
          || !result.path("tools").isArray()) {
        LOG.warn("upstream {} did not list its tools: {}", server.name(), response);
        return Optional.empty();
      }
      result.path("tools").forEach(tools::add);
      if (!result.path("nextCursor").isString()) {
        return Optional.of(tools);
      }
      params = json.createObjectNode().put("cursor", result.get("nextCursor").asString());
    }
    return Optional.of(tools);
  }

  /**
   * Returns how many upstream requests are waiting for an answer right now.
   *
   * @return the number of requests in flight
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public int inFlightRequests() {
    return inFlight.get();
  }

  /**
   * Builds the POST with the body, `_meta` and headers 2026-07-28 requires.
   *
   * @param server the upstream
   * @param method the JSON-RPC method
   * @param params the parameters
   * @param id the JSON-RPC id
   * @param credential the bearer credential to inject, if any
   * @return the HTTP request
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private HttpRequest buildRequest(
      UpstreamServer server,
      String method,
      ObjectNode params,
      long id,
      Optional<Secret> credential) {
    ObjectNode body = json.createObjectNode();
    body.put("jsonrpc", "2.0");
    body.put("id", id);
    body.put("method", method);
    ObjectNode copy = params.deepCopy();
    ObjectNode meta = copy.putObject("_meta");
    meta.put(McpProtocol.META_PROTOCOL_VERSION, McpProtocol.MODERN_VERSION);
    meta.putObject(McpProtocol.META_CLIENT_CAPABILITIES);
    meta.putObject(McpProtocol.META_CLIENT_INFO).put("name", "aduana").put("version", "0.1.0");
    body.set("params", copy);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(server.endpoint())
            .timeout(server.timeout())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header(McpProtocol.HEADER_PROTOCOL_VERSION, McpProtocol.MODERN_VERSION)
            .header(McpProtocol.HEADER_METHOD, method)
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
    if (params.path("name").isString()) {
      builder.header(McpProtocol.HEADER_NAME, params.get("name").asString());
    }
    credential.ifPresent(secret -> builder.header("Authorization", "Bearer " + secret.reveal()));
    return builder.build();
  }

  /**
   * Reads the JSON-RPC response for our id from a JSON body or an SSE stream.
   *
   * @param response the HTTP response
   * @param id the id the request was sent with
   * @return the interpreted answer
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private UpstreamResponse interpret(HttpResponse<byte[]> response, long id) {
    String contentType = response.headers().firstValue("content-type").orElse("");
    String text = new String(response.body(), StandardCharsets.UTF_8);
    Optional<JsonNode> message;
    try {
      message =
          contentType.startsWith("text/event-stream")
              ? ServerSentEvents.findResponse(text, id, json)
              : Optional.of(json.readTree(text));
    } catch (JacksonException exception) {
      return new UpstreamResponse.Unavailable(
          "HTTP " + response.statusCode() + ", body is not JSON");
    }
    if (message.isEmpty()) {
      return new UpstreamResponse.Unavailable("HTTP " + response.statusCode() + ", no response");
    }
    JsonNode node = message.get();
    if (node.has("error")) {
      JsonNode error = node.get("error");
      return new UpstreamResponse.RpcError(
          error.path("code").asInt(), error.path("message").asString());
    }
    if (response.statusCode() != 200 || !node.path("result").isObject()) {
      return new UpstreamResponse.Unavailable("HTTP " + response.statusCode() + ", no result");
    }
    return new UpstreamResponse.Result(node.get("result"));
  }

  /**
   * Names the failure without exposing the upstream's response body.
   *
   * @param cause why the exchange failed
   * @return a short description for the operator
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String describe(Throwable cause) {
    if (cause instanceof IOException) {
      return cause.getClass().getSimpleName();
    }
    return String.valueOf(cause);
  }
}
