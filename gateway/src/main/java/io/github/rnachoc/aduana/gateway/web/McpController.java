// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.web;

import io.github.rnachoc.aduana.gateway.config.AduanaProperties;
import io.github.rnachoc.aduana.gateway.mcp.McpEnvelope;
import io.github.rnachoc.aduana.gateway.mcp.McpException;
import io.github.rnachoc.aduana.gateway.mcp.McpProtocol;
import io.github.rnachoc.aduana.gateway.mcp.McpRequest;
import io.github.rnachoc.aduana.gateway.service.McpService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * The agent-facing MCP endpoint: Streamable HTTP, one JSON response per POST.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

  private final McpEnvelope envelope;
  private final McpService service;
  private final AduanaProperties properties;

  /**
   * Creates the controller.
   *
   * @param envelope parses requests and writes responses
   * @param service answers MCP methods
   * @param properties request size limits
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public McpController(McpEnvelope envelope, McpService service, AduanaProperties properties) {
    this.envelope = envelope;
    this.service = service;
    this.properties = properties;
  }

  /**
   * Handles one JSON-RPC message.
   *
   * @param body the request body, read up to the configured limit
   * @param protocolVersion the `MCP-Protocol-Version` header, if sent
   * @return the JSON-RPC response, or 202 for a notification
   * @throws IOException if the body cannot be read
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @PostMapping
  public ResponseEntity<byte[]> post(
      InputStream body,
      @RequestHeader(name = McpProtocol.HEADER_PROTOCOL_VERSION, required = false)
          Optional<String> protocolVersion)
      throws IOException {
    byte[] bytes = body.readNBytes(properties.limits().maxRequestBytes() + 1);
    if (bytes.length > properties.limits().maxRequestBytes()) {
      McpException tooLarge =
          new McpException(McpProtocol.INVALID_REQUEST, 413, "Request too large");
      return json(tooLarge.httpStatus(), envelope.error(Optional.empty(), tooLarge));
    }
    McpRequest request;
    try {
      request = envelope.parse(bytes, protocolVersion);
    } catch (McpException exception) {
      return json(exception.httpStatus(), envelope.error(Optional.empty(), exception));
    }
    if (request.isNotification()) {
      return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
    try {
      return json(200, envelope.result(request.id().orElseThrow(), service.handle(request)));
    } catch (McpException exception) {
      int status = request.era() == McpRequest.Era.MODERN ? exception.httpStatus() : 200;
      return json(status, envelope.error(request.id(), exception));
    }
  }

  /**
   * Refuses the GET stream and session deletion, which 2026-07-28 removed.
   *
   * @return 405
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @RequestMapping(method = {RequestMethod.GET, RequestMethod.DELETE})
  public ResponseEntity<Void> notAllowed() {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header("Allow", "POST").build();
  }

  /**
   * Wraps a JSON body in a response.
   *
   * @param status the HTTP status
   * @param body the JSON bytes
   * @return the response
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static ResponseEntity<byte[]> json(int status, byte[] body) {
    return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
  }
}
