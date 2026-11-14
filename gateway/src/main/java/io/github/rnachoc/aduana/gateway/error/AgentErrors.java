// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.error;

import io.github.rnachoc.aduana.gateway.mcp.McpException;
import io.github.rnachoc.aduana.gateway.mcp.McpProtocol;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns an {@link AgentErrorCode} into what the agent receives.
 *
 * <p>The specification makes an unknown tool a JSON-RPC error (-32602) and every other failure a
 * tool result with `isError: true`, so the model can read it (ADR 17). Both carry the same
 * structured `code`, `message` and `hint`.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Component
public class AgentErrors {

  private final JsonMapper json;

  /**
   * Creates the error builder.
   *
   * @param json the shared JSON mapper
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public AgentErrors(JsonMapper json) {
    this.json = json;
  }

  /**
   * Builds the tool result for a failed call.
   *
   * @param code the error code
   * @param extra additional structured fields, such as an approval id
   * @return a `tools/call` result flagged as an error
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ObjectNode toolResult(AgentErrorCode code, Map<String, String> extra) {
    ObjectNode structured = structured(code);
    extra.forEach(structured::put);
    ObjectNode result = json.createObjectNode();
    result.put("resultType", "complete");
    result.putArray("content").addObject().put("type", "text").put("text", structured.toString());
    result.set("structuredContent", structured);
    result.put("isError", true);
    return result;
  }

  /**
   * Builds the tool result for a failed call with no extra fields.
   *
   * @param code the error code
   * @return a `tools/call` result flagged as an error
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ObjectNode toolResult(AgentErrorCode code) {
    return toolResult(code, Map.of());
  }

  /**
   * Builds the protocol error for a tool that does not exist or is not visible.
   *
   * @return the exception to answer with
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public McpException toolNotFound() {
    AgentErrorCode code = AgentErrorCode.TOOL_NOT_FOUND;
    return new McpException(McpProtocol.INVALID_PARAMS, 200, code.message(), structured(code));
  }

  /**
   * Builds the structured `code`, `message` and `hint` object.
   *
   * @param code the error code
   * @return the structured error
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ObjectNode structured(AgentErrorCode code) {
    ObjectNode node = json.createObjectNode();
    node.put("code", code.name());
    node.put("message", code.message());
    node.put("hint", code.hint());
    return node;
  }
}
