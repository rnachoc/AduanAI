// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.mcp;

import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads agent requests and writes JSON-RPC responses, enforcing the 2026-07-28 envelope rules.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class McpEnvelope {

  private final JsonMapper json;

  /**
   * Creates the envelope codec.
   *
   * @param json the shared JSON mapper
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public McpEnvelope(JsonMapper json) {
    this.json = json;
  }

  /**
   * Parses a request body and decides its era from `_meta`, the headers and the method.
   *
   * <p>A request whose `_meta` names a revision is modern and its `MCP-Protocol-Version` header
   * must match it. A request without that key is legacy (ADR 4).
   *
   * @param body the raw body
   * @param protocolHeader the `MCP-Protocol-Version` header, if sent
   * @return the parsed request
   * @throws McpException when the body is not a JSON-RPC request or the headers contradict it
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public McpRequest parse(byte[] body, Optional<String> protocolHeader) {
    JsonNode root;
    try {
      root = json.readTree(body);
    } catch (JacksonException exception) {
      throw new McpException(McpProtocol.PARSE_ERROR, 400, "Parse error");
    }
    if (!root.isObject() || !root.path("method").isString()) {
      throw new McpException(McpProtocol.INVALID_REQUEST, 400, "Invalid request");
    }
    ObjectNode params =
        root.path("params").isObject() ? (ObjectNode) root.get("params") : json.createObjectNode();
    Optional<JsonNode> id = Optional.ofNullable(root.get("id")).filter(node -> !node.isNull());
    JsonNode version = params.path("_meta").path(McpProtocol.META_PROTOCOL_VERSION);
    if (!version.isString()) {
      return new McpRequest(id, root.get("method").asString(), params, McpRequest.Era.LEGACY);
    }
    if (protocolHeader.isPresent() && !protocolHeader.get().equals(version.asString())) {
      throw new McpException(
          McpProtocol.HEADER_MISMATCH, 400, "MCP-Protocol-Version does not match _meta");
    }
    if (!McpProtocol.MODERN_VERSION.equals(version.asString())) {
      ObjectNode data = json.createObjectNode();
      data.putArray("supported").add(McpProtocol.MODERN_VERSION).add(McpProtocol.LEGACY_VERSION);
      throw new McpException(
          McpProtocol.UNSUPPORTED_PROTOCOL_VERSION, 400, "Unsupported protocol version", data);
    }
    return new McpRequest(id, root.get("method").asString(), params, McpRequest.Era.MODERN);
  }

  /**
   * Writes a successful JSON-RPC response.
   *
   * @param id the request id
   * @param result the result object
   * @return the response body
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public byte[] result(JsonNode id, JsonNode result) {
    ObjectNode response = envelope(Optional.of(id));
    response.set("result", result);
    return json.writeValueAsBytes(response);
  }

  /**
   * Writes a JSON-RPC error response.
   *
   * @param id the request id; empty when it could not be read
   * @param error the protocol error
   * @return the response body
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public byte[] error(Optional<JsonNode> id, McpException error) {
    ObjectNode response = envelope(id);
    ObjectNode body = response.putObject("error");
    body.put("code", error.rpcCode());
    body.put("message", error.getMessage());
    error.data().ifPresent(data -> body.set("data", data));
    return json.writeValueAsBytes(response);
  }

  /**
   * Starts a response object with the JSON-RPC version and the id.
   *
   * @param id the request id; empty becomes a JSON null, as JSON-RPC asks
   * @return the response object
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ObjectNode envelope(Optional<JsonNode> id) {
    ObjectNode response = json.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id.orElseGet(json::nullNode));
    return response;
  }
}
