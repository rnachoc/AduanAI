// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.upstream;

import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the JSON-RPC response inside a `text/event-stream` body.
 *
 * <p>A 2026-07-28 server may send notifications about the request before the response on the same
 * stream, so the response is the event whose `id` matches the request.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
final class ServerSentEvents {

  /**
   * Prevents instances: the class only has static helpers.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ServerSentEvents() {}

  /**
   * Returns the event whose JSON-RPC id equals the request id.
   *
   * @param stream the full SSE body
   * @param id the request id
   * @param json the JSON mapper
   * @return the response message, if present
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static Optional<JsonNode> findResponse(String stream, long id, JsonMapper json) {
    // WHATWG HTML §9.2: events end at a blank line; several data lines join with a line feed.
    for (String event : stream.replace("\r\n", "\n").split("\n\n")) {
      StringBuilder data = new StringBuilder();
      for (String line : event.split("\n")) {
        if (line.startsWith("data:")) {
          String value = line.substring(5);
          data.append(data.isEmpty() ? "" : "\n")
              .append(value.startsWith(" ") ? value.substring(1) : value);
        }
      }
      if (data.isEmpty()) {
        continue;
      }
      JsonNode message = json.readTree(data.toString());
      if (message.path("id").asLong(-1) == id && (message.has("result") || message.has("error"))) {
        return Optional.of(message);
      }
    }
    return Optional.empty();
  }
}
