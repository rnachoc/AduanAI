// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.upstream;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One MCP server behind the gateway, as configured by the operator.
 *
 * @param name the server's name, the prefix of its tools as the agent sees them
 * @param endpoint the server's Streamable HTTP endpoint
 * @param timeout how long one call may take before the gateway gives up
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record UpstreamServer(String name, URI endpoint, Duration timeout) {

  /** Lowercase, starting with a letter, and never containing `__`, so tool names split cleanly. */
  public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

  /** The timeout used when the configuration names none. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Validates the configuration, so a bad entry stops the gateway at start-up.
   *
   * @param name the server's name
   * @param endpoint the server's endpoint
   * @param timeout the call timeout; the default applies when absent
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public UpstreamServer {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(endpoint, "endpoint");
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("Upstream name must match " + NAME + ": " + name);
    }
    if (!"http".equals(endpoint.getScheme()) && !"https".equals(endpoint.getScheme())) {
      throw new IllegalArgumentException("Upstream endpoint must be http or https: " + endpoint);
    }
    timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("Upstream timeout must be positive: " + name);
    }
  }
}
