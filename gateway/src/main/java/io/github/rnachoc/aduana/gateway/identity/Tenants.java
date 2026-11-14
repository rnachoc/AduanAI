// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.identity;

import java.util.UUID;

/**
 * The tenant the open version runs everything in. The schema is multi-tenant from the start
 * (principle 10); only this one row exists.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class Tenants {

  /** The id of the single tenant created by migration V1. */
  public static final UUID DEFAULT = UUID.fromString("00000000-0000-0000-0000-000000000001");

  /**
   * Prevents instances: the class only holds a constant.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Tenants() {}
}
