// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.cli.attacks;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.aduana.audit.ChainVerifier;
import io.github.rnachoc.aduana.audit.Checkpoints;
import io.github.rnachoc.aduana.audit.HashChain;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Phase 11: the parts of the attack bench that need no gateway: loading cases, checking the plan's
 * minimums, judging expectations, tampering with an exported log, and writing the report.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
class AttackBenchTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void the_published_cases_meet_the_minimum_per_category() throws IOException {
    List<AttackCase> cases = AttackCase.load(Path.of("../attacks/cases"));
    assertThat(cases).hasSizeGreaterThanOrEqualTo(80);
    Map<String, Long> counts = new TreeMap<>();
    cases.forEach(c -> counts.merge(c.category(), 1L, Long::sum));
    AttackCase.MINIMUMS.forEach((category, minimum) ->
        assertThat(counts.getOrDefault(category, 0L)).as(category).isGreaterThanOrEqualTo(minimum));
    assertThat(counts.keySet()).isSubsetOf(AttackCase.MINIMUMS.keySet());
    assertThat(cases.stream().map(AttackCase::id).distinct()).hasSameSizeAs(cases);
    assertThat(cases).allMatch(c -> !c.defence().isBlank() && !c.title().isBlank());
  }

  @Test
  void a_case_file_with_an_unknown_step_is_refused() throws IOException {
    Path directory = Files.createTempDirectory("cases");
    Files.writeString(
        directory.resolve("bad.yaml"),
        """
        id: X-01
        category: admin-routes
        title: t
        defence: d
        deterministic: true
        steps:
          - teleport: somewhere
        """);
    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> AttackCase.load(directory)))
        .hasMessageContaining("X-01")
        .hasMessageContaining("teleport");
  }

  @Test
  void expectations_match_codes_statuses_and_problems() {
    ObjectNode expect = JSON.createObjectNode();
    expect.putArray("code").add("POLICY_DENIED").add("APPROVAL_PENDING");
    assertThat(Expectations.met(expect, Outcome.code("APPROVAL_PENDING"))).isTrue();
    assertThat(Expectations.met(expect, Outcome.code("OK"))).isFalse();
    ObjectNode status = JSON.createObjectNode();
    status.putArray("status").add(401).add(403);
    assertThat(Expectations.met(status, Outcome.status(403))).isTrue();
    assertThat(Expectations.met(status, Outcome.status(200))).isFalse();
    ObjectNode notOk = JSON.createObjectNode().put("not_code", "OK");
    assertThat(Expectations.met(notOk, Outcome.code("RATE_LIMITED"))).isTrue();
    ObjectNode problem = JSON.createObjectNode();
    problem.putArray("problem").add("HASH_MISMATCH");
    assertThat(Expectations.met(problem, Outcome.problems(List.of("HASH_MISMATCH", "BROKEN_LINK"))))
        .isTrue();
    assertThat(Expectations.met(problem, Outcome.problems(List.of()))).isFalse();
  }

  @Test
  void every_tampering_is_detected_by_the_verifier() throws Exception {
    KeyPair keys = Checkpoints.generateKeyPair();
    List<JsonNode> entries = chain(6);
    List<JsonNode> checkpoints =
        List.of(
            Checkpoints.sign(
                keys.getPrivate(),
                "k",
                "t",
                3,
                entries.get(2).path("hash").asString(),
                "2026-11-15T00:00:03Z"),
            Checkpoints.sign(
                keys.getPrivate(),
                "k",
                "t",
                6,
                entries.get(5).path("hash").asString(),
                "2026-11-15T00:00:06Z"));
    assertThat(ChainVerifier.verify(entries, checkpoints, Optional.of(keys.getPublic())).intact())
        .isTrue();
    for (Tampering tampering : Tampering.values()) {
      Tampering.Tampered tampered = tampering.apply(entries, checkpoints);
      ChainVerifier.Report report =
          ChainVerifier.verify(
              tampered.entries(), tampered.checkpoints(), Optional.of(keys.getPublic()));
      assertThat(report.intact()).as(tampering.name()).isFalse();
    }
  }

  @Test
  void the_report_is_stable_and_lists_what_escaped() {
    AttackCase deterministic =
        new AttackCase(
            "ADM-01",
            "admin-routes",
            "Agent key on the admin API",
            "Session-only API",
            true,
            List.of(),
            "a.yaml");
    AttackCase heuristic =
        new AttackCase(
            "INJ-01",
            "tool-result-injection",
            "Paraphrased instruction",
            "Injection patterns",
            false,
            List.of(),
            "b.yaml");
    List<CaseResult> results =
        List.of(
            new CaseResult(deterministic, true, List.of("HTTP 403")),
            new CaseResult(heuristic, false, List.of("OK")));
    String report = Report.render(results);
    assertThat(report).isEqualTo(Report.render(new ArrayList<>(results).reversed()));
    assertThat(report).contains("| ADM-01 |").contains("NOT STOPPED");
    assertThat(report).contains("Deterministic defences: 1 of 1 cases stopped");
    assertThat(report).contains("Heuristic defences: 0 of 1 cases stopped");
    assertThat(report).doesNotContain("T0").doesNotContain("2026-");
    assertThat(Report.failures(results)).isEmpty();
    assertThat(Report.failures(List.of(new CaseResult(deterministic, false, List.of("OK")))))
        .containsExactly("ADM-01");
  }

  /**
   * Builds a valid chain of entries.
   *
   * @param count how many
   * @return the entries
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static List<JsonNode> chain(int count) {
    List<JsonNode> entries = new ArrayList<>();
    String previous = HashChain.GENESIS;
    for (int seq = 1; seq <= count; seq++) {
      ObjectNode entry = JSON.createObjectNode();
      entry.put("tenant_id", "t");
      entry.put("seq", seq);
      entry.put("ts", "2026-11-15T00:00:0" + seq + "Z");
      entry.put("type", "CALL_DECIDED");
      entry.put("tool", "payments__refund");
      entry.putObject("args_redacted").put("amount_cents", seq * 100);
      entry.put("prev_hash", previous);
      entry.put("hash", HashChain.hash(entry));
      previous = entry.get("hash").asString();
      entries.add(entry);
    }
    return entries;
  }
}
