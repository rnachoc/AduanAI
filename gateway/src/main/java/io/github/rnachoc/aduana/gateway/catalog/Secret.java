// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.catalog;

import java.util.Objects;

/**
 * A credential in memory. Its `toString` never prints the value, so it cannot leak into logs or
 * error messages by accident.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class Secret {

  private final String value;

  /**
   * Wraps a credential.
   *
   * @param value the credential
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Secret(String value) {
    this.value = Objects.requireNonNull(value, "value");
  }

  /**
   * Returns the credential, for the one place that injects it into an upstream request.
   *
   * @return the credential
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public String reveal() {
    return value;
  }

  /**
   * Prints a placeholder instead of the credential.
   *
   * @return `[secret]`
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Override
  public String toString() {
    return "[secret]";
  }
}
