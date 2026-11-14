// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.upstream;

import tools.jackson.databind.JsonNode;

/**
 * What came back from one request to an upstream MCP server.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public sealed interface UpstreamResponse {

  /**
   * The upstream answered with a JSON-RPC result.
   *
   * @param result the result object
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  record Result(JsonNode result) implements UpstreamResponse {}

  /**
   * The upstream answered with a JSON-RPC error.
   *
   * @param code the JSON-RPC error code
   * @param message the error message
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  record RpcError(int code, String message) implements UpstreamResponse {}

  /**
   * The upstream could not be reached, or answered with something that is not JSON-RPC.
   *
   * @param detail what went wrong, for the operator's log only
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  record Unavailable(String detail) implements UpstreamResponse {}

  /**
   * The upstream did not answer within its timeout; the request was cancelled.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  record TimedOut() implements UpstreamResponse {}
}
