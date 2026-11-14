// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.service;

import io.github.rnachoc.aduana.gateway.upstream.UpstreamServer;
import java.util.Optional;

/**
 * A tool as the agent names it: `<server>__<tool>`.
 *
 * @param server the upstream server's name
 * @param tool the tool's name on that server
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record ToolName(String server, String tool) {

  private static final String SEPARATOR = "__";

  /**
   * Splits an agent-facing name at the first `__`; server names cannot contain it.
   *
   * @param qualified the name the agent used
   * @return the parts, or empty when the name has no valid server prefix or no tool part
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static Optional<ToolName> parse(String qualified) {
    int split = qualified.indexOf(SEPARATOR);
    if (split <= 0 || split + SEPARATOR.length() >= qualified.length()) {
      return Optional.empty();
    }
    String server = qualified.substring(0, split);
    if (!UpstreamServer.NAME.matcher(server).matches()) {
      return Optional.empty();
    }
    return Optional.of(new ToolName(server, qualified.substring(split + SEPARATOR.length())));
  }

  /**
   * Returns the name the agent sees.
   *
   * @return `<server>__<tool>`
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public String qualified() {
    return server + SEPARATOR + tool;
  }
}
