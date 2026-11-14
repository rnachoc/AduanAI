// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Proves the cli module compiles and its tests run.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
class CliModuleTest {

  @Test
  void the_module_knows_its_name() {
    assertThat(CliModule.NAME).isEqualTo("aduana-cli");
  }
}
