// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.mcp;

import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * A request the gateway refuses at the protocol level, answered as a JSON-RPC error.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class McpException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int rpcCode;
  private final int httpStatus;
  private final transient JsonNode data;

  /**
   * Creates a protocol error with structured data.
   *
   * @param rpcCode the JSON-RPC error code
   * @param httpStatus the HTTP status the specification asks for
   * @param message the error message
   * @param data structured error data
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public McpException(int rpcCode, int httpStatus, String message, JsonNode data) {
    super(message);
    this.rpcCode = rpcCode;
    this.httpStatus = httpStatus;
    this.data = Objects.requireNonNull(data, "data");
  }

  /**
   * Creates a protocol error without data.
   *
   * @param rpcCode the JSON-RPC error code
   * @param httpStatus the HTTP status the specification asks for
   * @param message the error message
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public McpException(int rpcCode, int httpStatus, String message) {
    super(message);
    this.rpcCode = rpcCode;
    this.httpStatus = httpStatus;
    this.data = null;
  }

  /**
   * Returns the JSON-RPC error code.
   *
   * @return the code
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public int rpcCode() {
    return rpcCode;
  }

  /**
   * Returns the HTTP status to answer with.
   *
   * @return the status
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public int httpStatus() {
    return httpStatus;
  }

  /**
   * Returns the structured error data, when there is any.
   *
   * @return the data
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<JsonNode> data() {
    return Optional.ofNullable(data);
  }
}
