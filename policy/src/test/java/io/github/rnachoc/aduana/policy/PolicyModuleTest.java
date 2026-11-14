// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Proves the policy module compiles and its tests run.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
class PolicyModuleTest {

  @Test
  void the_module_knows_its_name() {
    assertThat(PolicyModule.NAME).isEqualTo("aduana-policy");
  }
}
