// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * One policy rule: when it matches, and what it asks for.
 *
 * @param id the unique rule id
 * @param description what the rule is for, for people
 * @param match the conditions, all of which must hold
 * @param effect allow, require approval or deny
 * @param obligations what the gateway must do when the call goes ahead
 * @param approval who approves and for how long, for require_approval rules
 * @param reasonCode a stable code recorded with a deny
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public record Rule(
    String id,
    String description,
    Match match,
    Effect effect,
    Obligations obligations,
    ApprovalSpec approval,
    Optional<String> reasonCode) {

  /**
   * Requires every part.
   *
   * @param id the id
   * @param description the description
   * @param match the conditions
   * @param effect the effect
   * @param obligations the obligations
   * @param approval the approval settings
   * @param reasonCode the reason code
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Rule {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(match, "match");
    Objects.requireNonNull(effect, "effect");
    Objects.requireNonNull(obligations, "obligations");
    Objects.requireNonNull(approval, "approval");
  }

  /**
   * All the conditions of a rule; an absent part matches everything.
   *
   * @param principal conditions on the caller
   * @param tool conditions on the tool
   * @param args conditions on the arguments
   * @param context conditions on the data-flow marks
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Match(
      PrincipalMatch principal, ToolMatch tool, List<ArgCondition> args, ContextMatch context) {

    /**
     * Copies the condition list.
     *
     * @param principal caller conditions
     * @param tool tool conditions
     * @param args argument conditions
     * @param context mark conditions
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Match {
      args = List.copyOf(args);
    }
  }

  /**
   * Conditions on the caller.
   *
   * @param name the principal name
   * @param attributes attribute name to matcher; every one must match
   * @param anyGroup the principal must be in at least one of these groups, when not empty
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record PrincipalMatch(
      Optional<StringMatcher> name, Map<String, StringMatcher> attributes, List<String> anyGroup) {

    /** Matches every caller. */
    public static final PrincipalMatch ANY =
        new PrincipalMatch(Optional.empty(), Map.of(), List.of());

    /**
     * Copies the collections.
     *
     * @param name the name matcher
     * @param attributes the attribute matchers
     * @param anyGroup the groups
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public PrincipalMatch {
      attributes = Map.copyOf(attributes);
      anyGroup = List.copyOf(anyGroup);
    }

    /**
     * Tells whether a caller satisfies every condition.
     *
     * @param principal the caller
     * @return true when all hold
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public boolean matches(CallContext.PrincipalFacts principal) {
      if (name.isPresent() && !name.get().matches(Optional.of(principal.name()))) {
        return false;
      }
      for (Map.Entry<String, StringMatcher> attribute : attributes.entrySet()) {
        if (!attribute.getValue().matches(principal.attribute(attribute.getKey()))) {
          return false;
        }
      }
      return anyGroup.isEmpty() || anyGroup.stream().anyMatch(principal.groups()::contains);
    }
  }

  /**
   * Conditions on the tool and its catalog metadata.
   *
   * @param server the server name
   * @param name the tool name
   * @param sideEffect the required side-effect flag
   * @param untrustedSource the required untrusted-source flag
   * @param sensitivity the sensitivity
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record ToolMatch(
      Optional<StringMatcher> server,
      Optional<StringMatcher> name,
      Optional<Boolean> sideEffect,
      Optional<Boolean> untrustedSource,
      Optional<StringMatcher> sensitivity) {

    /** Matches every tool. */
    public static final ToolMatch ANY =
        new ToolMatch(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    /**
     * Tells whether a tool satisfies every condition.
     *
     * @param tool the tool
     * @return true when all hold
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public boolean matches(CallContext.ToolFacts tool) {
      return server.map(m -> m.matches(Optional.of(tool.server()))).orElse(true)
          && name.map(m -> m.matches(Optional.of(tool.name()))).orElse(true)
          && sideEffect.map(flag -> flag == tool.sideEffect()).orElse(true)
          && untrustedSource.map(flag -> flag == tool.untrustedSource()).orElse(true)
          && sensitivity.map(m -> m.matches(Optional.of(tool.sensitivity()))).orElse(true);
    }
  }

  /**
   * Conditions on the data-flow marks.
   *
   * @param flowFromUntrusted the required flow mark
   * @param contextTainted the required taint mark
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record ContextMatch(
      Optional<Boolean> flowFromUntrusted, Optional<Boolean> contextTainted) {

    /** Matches every context. */
    public static final ContextMatch ANY = new ContextMatch(Optional.empty(), Optional.empty());

    /**
     * Tells whether the marks satisfy every condition.
     *
     * @param context the marks
     * @return true when all hold
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public boolean matches(CallContext.ContextFacts context) {
      return flowFromUntrusted.map(flag -> flag == context.flowFromUntrusted()).orElse(true)
          && contextTainted.map(flag -> flag == context.contextTainted()).orElse(true);
    }

    /**
     * Tells whether the rule says anything about the marks.
     *
     * @return true when at least one mark is required
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public boolean isUnconditional() {
      return flowFromUntrusted.isEmpty() && contextTainted.isEmpty();
    }
  }

  /**
   * What the gateway must do with a call that goes ahead; detector ids from plan §5.7.
   *
   * @param redactResult detectors whose matches are replaced in the result
   * @param denyArgsContaining detectors that deny the call when found in the arguments
   * @param flagArgsContaining detectors that only flag the call when found in the arguments
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Obligations(
      Set<String> redactResult, Set<String> denyArgsContaining, Set<String> flagArgsContaining) {

    /** No obligations. */
    public static final Obligations NONE = new Obligations(Set.of(), Set.of(), Set.of());

    /**
     * Copies the sets in sorted order, so equal obligations print the same.
     *
     * @param redactResult detectors to redact
     * @param denyArgsContaining detectors that deny
     * @param flagArgsContaining detectors that flag
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Obligations {
      redactResult = Collections.unmodifiableSet(new TreeSet<>(redactResult));
      denyArgsContaining = Collections.unmodifiableSet(new TreeSet<>(denyArgsContaining));
      flagArgsContaining = Collections.unmodifiableSet(new TreeSet<>(flagArgsContaining));
    }

    /**
     * Joins two sets of obligations.
     *
     * @param other the other obligations
     * @return the union
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public Obligations union(Obligations other) {
      return new Obligations(
          join(redactResult, other.redactResult),
          join(denyArgsContaining, other.denyArgsContaining),
          join(flagArgsContaining, other.flagArgsContaining));
    }

    /**
     * Joins two sets.
     *
     * @param a one set
     * @param b another set
     * @return the union
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    private static Set<String> join(Set<String> a, Set<String> b) {
      Set<String> union = new TreeSet<>(a);
      union.addAll(b);
      return union;
    }
  }

  /**
   * Who approves a call and how long an approval stays valid.
   *
   * @param approversGroup the console group whose members may decide
   * @param expiresIn how long a pending or granted approval lasts
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record ApprovalSpec(String approversGroup, Duration expiresIn) {

    /** Used when a require_approval rule names no approval block. */
    public static final ApprovalSpec DEFAULT =
        new ApprovalSpec("approvers", Duration.ofMinutes(15));
  }
}
