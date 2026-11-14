// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.service;

import io.github.rnachoc.aduana.gateway.config.AduanaProperties;
import io.github.rnachoc.aduana.gateway.error.AgentErrorCode;
import io.github.rnachoc.aduana.gateway.error.AgentErrors;
import io.github.rnachoc.aduana.gateway.mcp.McpProtocol;
import io.github.rnachoc.aduana.gateway.upstream.UpstreamClient;
import io.github.rnachoc.aduana.gateway.upstream.UpstreamResponse;
import io.github.rnachoc.aduana.gateway.upstream.UpstreamServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs one `tools/call` from an agent: resolve the tool, check the arguments, forward, map errors.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Service
public class ToolCallService {

  private static final Logger LOG = LoggerFactory.getLogger(ToolCallService.class);

  private final AduanaProperties properties;
  private final UpstreamClient upstreams;
  private final AgentErrors errors;
  private final JsonMapper json;

  /**
   * Creates the service.
   *
   * @param properties the configured upstreams and limits
   * @param upstreams the upstream client
   * @param errors builds agent-facing errors
   * @param json the shared JSON mapper
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ToolCallService(
      AduanaProperties properties, UpstreamClient upstreams, AgentErrors errors, JsonMapper json) {
    this.properties = properties;
    this.upstreams = upstreams;
    this.errors = errors;
    this.json = json;
  }

  /**
   * Runs a call and returns the `tools/call` result for the agent.
   *
   * @param params the agent's `tools/call` parameters
   * @return the upstream's result, or an error result
   * @throws io.github.rnachoc.aduana.gateway.mcp.McpException TOOL_NOT_FOUND for an unknown tool
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public JsonNode call(ObjectNode params) {
    ToolName name =
        ToolName.parse(params.path("name").asString("")).orElseThrow(errors::toolNotFound);
    UpstreamServer server = properties.upstream(name.server()).orElseThrow(errors::toolNotFound);
    JsonNode arguments = params.path("arguments");
    if (!arguments.isMissingNode() && !arguments.isObject()) {
      return errors.toolResult(AgentErrorCode.ARGS_INVALID);
    }
    ObjectNode forwardedArguments =
        arguments.isObject() ? (ObjectNode) arguments : json.createObjectNode();
    if (json.writeValueAsBytes(forwardedArguments).length
        > properties.limits().maxArgumentsBytes()) {
      return errors.toolResult(AgentErrorCode.ARGS_INVALID);
    }
    ObjectNode forward = json.createObjectNode();
    forward.put("name", name.tool());
    forward.set("arguments", forwardedArguments);
    return interpret(server, upstreams.send(server, "tools/call", forward));
  }

  /**
   * Maps the upstream's answer to what the agent receives.
   *
   * @param server the upstream, for the operator log
   * @param response the upstream's answer
   * @return the agent-facing result
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private JsonNode interpret(UpstreamServer server, UpstreamResponse response) {
    return switch (response) {
      case UpstreamResponse.Result(JsonNode result) -> result;
      case UpstreamResponse.RpcError(int code, String message)
          when code == McpProtocol.INVALID_PARAMS && message.startsWith("Unknown tool") ->
          throw errors.toolNotFound();
      case UpstreamResponse.RpcError(int code, String message) -> {
        LOG.warn("upstream {} answered error {}", server.name(), code);
        yield errors.toolResult(AgentErrorCode.UPSTREAM_ERROR);
      }
      case UpstreamResponse.Unavailable(String detail) -> {
        LOG.warn("upstream {} unavailable: {}", server.name(), detail);
        yield errors.toolResult(AgentErrorCode.UPSTREAM_ERROR);
      }
      case UpstreamResponse.TimedOut() -> {
        LOG.warn("upstream {} timed out after {}", server.name(), server.timeout());
        yield errors.toolResult(AgentErrorCode.UPSTREAM_TIMEOUT);
      }
    };
  }
}
