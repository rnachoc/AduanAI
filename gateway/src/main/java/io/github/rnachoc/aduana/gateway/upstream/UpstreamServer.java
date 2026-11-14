// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.upstream;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One MCP server behind the gateway, as configured by the operator.
 *
 * @param name the server's name, the prefix of its tools as the agent sees them
 * @param endpoint the server's Streamable HTTP endpoint, or `stdio:/path/to/command?arg=a&arg=b`
 *     for a local server the gateway runs as a child process
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
    boolean stdio = "stdio".equals(endpoint.getScheme());
    if (!stdio && !"http".equals(endpoint.getScheme()) && !"https".equals(endpoint.getScheme())) {
      throw new IllegalArgumentException(
          "Upstream endpoint must be http, https or stdio: " + endpoint);
    }
    if (stdio && (endpoint.getPath() == null || !endpoint.getPath().startsWith("/"))) {
      throw new IllegalArgumentException(
          "A stdio endpoint names an absolute command path: " + name);
    }
    timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("Upstream timeout must be positive: " + name);
    }
  }

  /**
   * Says whether the gateway runs this server as a child process.
   *
   * @return true for a `stdio:` endpoint
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public boolean stdio() {
    return "stdio".equals(endpoint.getScheme());
  }

  /**
   * The command line of a stdio server: the path, then each `arg` of the query, in order.
   *
   * @return the command and its arguments
   * @throws IllegalStateException for an http server
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<String> command() {
    if (!stdio()) {
      throw new IllegalStateException("Not a stdio server: " + name);
    }
    List<String> command = new ArrayList<>();
    command.add(endpoint.getPath());
    String query = endpoint.getRawQuery();
    if (query != null) {
      for (String part : query.split("&")) {
        if (part.startsWith("arg=")) {
          command.add(URLDecoder.decode(part.substring(4), StandardCharsets.UTF_8));
        }
      }
    }
    return List.copyOf(command);
  }
}
