// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.config;

import io.github.rnachoc.aduana.gateway.upstream.UpstreamServer;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The gateway's configuration under the `aduana` prefix.
 *
 * @param upstreams the MCP servers behind the gateway
 * @param limits size limits applied before anything is forwarded
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@ConfigurationProperties("aduana")
public record AduanaProperties(List<UpstreamServer> upstreams, Limits limits) {

  /**
   * Size limits for agent requests.
   *
   * @param maxRequestBytes the largest request body accepted
   * @param maxArgumentsBytes the largest serialised `arguments` object a tool call may carry
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Limits(int maxRequestBytes, int maxArgumentsBytes) {

    /**
     * Fills in defaults of 1 MiB per request and 64 KiB of arguments.
     *
     * @param maxRequestBytes the largest request body; 0 for the default
     * @param maxArgumentsBytes the largest arguments object; 0 for the default
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Limits {
      maxRequestBytes = maxRequestBytes > 0 ? maxRequestBytes : 1_048_576;
      maxArgumentsBytes = maxArgumentsBytes > 0 ? maxArgumentsBytes : 65_536;
    }
  }

  /**
   * Applies defaults and refuses duplicate server names.
   *
   * @param upstreams the configured servers; none when absent
   * @param limits the limits; defaults when absent
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public AduanaProperties {
    upstreams = upstreams == null ? List.of() : List.copyOf(upstreams);
    limits = limits == null ? new Limits(0, 0) : limits;
    Set<String> names = new HashSet<>();
    for (UpstreamServer server : upstreams) {
      if (!names.add(server.name())) {
        throw new IllegalArgumentException("Duplicate upstream name: " + server.name());
      }
    }
  }

  /**
   * Finds a configured upstream by name.
   *
   * @param name the server name
   * @return the server, if configured
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Optional<UpstreamServer> upstream(String name) {
    return upstreams.stream().filter(server -> server.name().equals(name)).findFirst();
  }
}
