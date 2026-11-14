// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.service;

import io.github.rnachoc.aduana.gateway.identity.Principal;
import io.github.rnachoc.aduana.gateway.mcp.McpException;
import io.github.rnachoc.aduana.gateway.mcp.McpProtocol;
import io.github.rnachoc.aduana.gateway.mcp.McpRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Answers the MCP methods the gateway supports (plan §6.1) and refuses the rest.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Service
public class McpService {

  private final ToolListService toolList;
  private final ToolCallService toolCall;
  private final JsonMapper json;

  /**
   * Creates the service.
   *
   * @param toolList builds tool lists
   * @param toolCall runs tool calls
   * @param json the shared JSON mapper
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public McpService(ToolListService toolList, ToolCallService toolCall, JsonMapper json) {
    this.toolList = toolList;
    this.toolCall = toolCall;
    this.json = json;
  }

  /**
   * Handles one request and returns its result object.
   *
   * @param principal the authenticated caller
   * @param request the parsed request
   * @return the JSON-RPC result
   * @throws McpException for unsupported methods and protocol errors
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public JsonNode handle(Principal principal, McpRequest request) {
    return switch (request.method()) {
      case "server/discover" -> discover();
      case "initialize" -> initialize();
      case "ping" -> json.createObjectNode();
      case "tools/list" -> toolList.listTools(principal);
      case "tools/call" -> toolCall.call(principal, request.params());
      case "resources/list" -> emptyList("resources");
      case "resources/templates/list" -> emptyList("resourceTemplates");
      case "prompts/list" -> emptyList("prompts");
      default -> throw new McpException(McpProtocol.METHOD_NOT_FOUND, 404, "Method not found");
    };
  }

  /**
   * Builds the `server/discover` result.
   *
   * @return the result
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ObjectNode discover() {
    ObjectNode result = json.createObjectNode();
    result.put("resultType", "complete");
    result.putArray("supportedVersions").add(McpProtocol.MODERN_VERSION);
    result.putObject("capabilities").putObject("tools");
    result.putObject("_meta").set(McpProtocol.META_SERVER_INFO, serverInfo());
    return result;
  }

  /**
   * Builds the minimal 2025-11-25 `initialize` result that lets older clients work (ADR 4).
   *
   * @return the result
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ObjectNode initialize() {
    ObjectNode result = json.createObjectNode();
    result.put("protocolVersion", McpProtocol.LEGACY_VERSION);
    result.putObject("capabilities").putObject("tools");
    result.set("serverInfo", serverInfo());
    return result;
  }

  /**
   * Builds an empty list result, for the method families out of scope in version 1.
   *
   * @param field the list's field name
   * @return the result
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ObjectNode emptyList(String field) {
    ObjectNode result = json.createObjectNode();
    result.put("resultType", "complete");
    result.putArray(field);
    return result;
  }

  /**
   * Names the gateway.
   *
   * @return the server info object
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ObjectNode serverInfo() {
    return json.createObjectNode().put("name", "aduana").put("version", "0.1.0");
  }
}
