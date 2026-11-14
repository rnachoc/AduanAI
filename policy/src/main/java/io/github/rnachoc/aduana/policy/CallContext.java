// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Everything the evaluator may look at for one call. It holds facts computed by the gateway, so the
 * evaluator itself reads no clock, no database and no network.
 *
 * @param principal who calls
 * @param tool what is called
 * @param arguments the call's arguments object
 * @param context marks about the data flow
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record CallContext(
    PrincipalFacts principal, ToolFacts tool, JsonNode arguments, ContextFacts context) {

  /**
   * Requires every part.
   *
   * @param principal who calls
   * @param tool what is called
   * @param arguments the arguments
   * @param context the data-flow marks
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public CallContext {
    Objects.requireNonNull(principal, "principal");
    Objects.requireNonNull(tool, "tool");
    Objects.requireNonNull(arguments, "arguments");
    Objects.requireNonNull(context, "context");
  }

  /**
   * The caller.
   *
   * @param name the principal's name
   * @param attributes its attributes
   * @param groups its groups
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record PrincipalFacts(String name, Map<String, String> attributes, Set<String> groups) {

    /**
     * Copies the collections.
     *
     * @param name the name
     * @param attributes the attributes
     * @param groups the groups
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public PrincipalFacts {
      Objects.requireNonNull(name, "name");
      attributes = Map.copyOf(attributes);
      groups = Set.copyOf(groups);
    }

    /**
     * Reads one attribute.
     *
     * @param key the attribute name
     * @return its value, if set
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Optional<String> attribute(String key) {
      return Optional.ofNullable(attributes.get(key));
    }
  }

  /**
   * The tool and the metadata the administrator gave it in the catalog.
   *
   * @param server the server name
   * @param name the tool name on that server
   * @param sideEffect whether it changes something
   * @param untrustedSource whether its results are untrusted
   * @param sensitivity low, medium or high
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record ToolFacts(
      String server,
      String name,
      boolean sideEffect,
      boolean untrustedSource,
      String sensitivity) {}

  /**
   * Marks about where the arguments' data came from (plan §5.6).
   *
   * @param flowFromUntrusted a value in the arguments came from an untrusted tool's result
   * @param contextTainted the principal received untrusted data recently
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record ContextFacts(boolean flowFromUntrusted, boolean contextTainted) {

    /** No marks. */
    public static final ContextFacts CLEAN = new ContextFacts(false, false);
  }
}
