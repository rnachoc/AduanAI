// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The agent-facing tool name is `<server>__<tool>`; server names never contain `__`.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
class ToolNameTest {

  @Test
  void a_namespaced_name_splits_at_the_first_double_underscore() {
    assertThat(ToolName.parse("crm__get_customer")).contains(new ToolName("crm", "get_customer"));
    assertThat(ToolName.parse("files__read__raw")).contains(new ToolName("files", "read__raw"));
  }

  @Test
  void a_name_without_a_server_part_does_not_parse() {
    assertThat(ToolName.parse("get_customer")).isEmpty();
    assertThat(ToolName.parse("__x")).isEmpty();
    assertThat(ToolName.parse("crm__")).isEmpty();
    assertThat(ToolName.parse("Bad-Server__x")).isEmpty();
  }

  @Test
  void formatting_gives_back_the_agent_facing_name() {
    assertThat(new ToolName("payments", "refund").qualified()).isEqualTo("payments__refund");
  }
}
