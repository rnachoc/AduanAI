// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.mcp;

import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One parsed JSON-RPC message from an agent, with the protocol era it was sent in.
 *
 * @param id the request id; empty for a notification
 * @param method the JSON-RPC method
 * @param params the parameters object (empty when absent)
 * @param era which revision's rules apply
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record McpRequest(Optional<JsonNode> id, String method, ObjectNode params, Era era) {

  /**
   * The revision a request follows.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public enum Era {
    /** 2026-07-28: version and capabilities in `_meta` on every request. */
    MODERN,
    /** 2025-11-25: an `initialize` handshake set them once. */
    LEGACY
  }

  /**
   * Tells whether the message is a notification, which gets no response.
   *
   * @return true when there is no id
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public boolean isNotification() {
    return id.isEmpty();
  }

  /**
   * Returns the request's `_meta` object, or an empty one.
   *
   * @return the metadata
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public JsonNode meta() {
    return params.path("_meta");
  }
}
