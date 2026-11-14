// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

/**
 * Names the policy module so the build has something to compile before its first feature lands.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class PolicyModule {

  /** The module name as it appears in the Maven reactor. */
  public static final String NAME = "aduana-policy";

  /**
   * Prevents instances: the class only carries the module name.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private PolicyModule() {}
}
