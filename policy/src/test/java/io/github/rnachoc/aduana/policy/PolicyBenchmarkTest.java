// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Times the evaluator on a 200-rule policy (CA4.5) and writes the measured numbers to
 * `policy/target/policy-benchmark.txt`. Tagged so it runs only with `make bench-policy`.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Tag("benchmark")
class PolicyBenchmarkTest {

  private static final int RULES = 200;
  private static final int WARMUP = 20_000;
  private static final int SAMPLES = 20_000;

  @Test
  void a_two_hundred_rule_policy_decides_in_under_a_millisecond_at_the_median() throws IOException {
    StringBuilder rules = new StringBuilder();
    for (int index = 0; index < RULES; index++) {
      rules
          .append("  - id: r")
          .append(index)
          .append('\n')
          .append("    match:\n")
          .append("      principal: { attributes: { team: team")
          .append(index % 10)
          .append(" } }\n")
          .append("      tool: { server: server")
          .append(index % 20)
          .append(", name: { in: [a, b, c] } }\n")
          .append("      args: [ { path: \"$.amount\", gt: ")
          .append(index * 10)
          .append(" } ]\n")
          .append("    effect: ")
          .append(index % 7 == 0 ? "deny" : index % 3 == 0 ? "require_approval" : "allow")
          .append('\n');
    }
    PolicyBundle bundle = Policies.bundle(rules.toString());
    CallContext call =
        Policies.call(
            Map.of("team", "team3"),
            "server13",
            "b",
            "{\"amount\": 950}",
            CallContext.ContextFacts.CLEAN);
    for (int round = 0; round < WARMUP; round++) {
      PolicyEvaluator.decide(bundle, call);
    }
    long[] nanos = new long[SAMPLES];
    for (int round = 0; round < SAMPLES; round++) {
      long start = System.nanoTime();
      PolicyEvaluator.decide(bundle, call);
      nanos[round] = System.nanoTime() - start;
    }
    Arrays.sort(nanos);
    long median = nanos[SAMPLES / 2];
    long p99 = nanos[(int) (SAMPLES * 0.99)];
    String report =
        "rules="
            + RULES
            + " samples="
            + SAMPLES
            + " median_us="
            + median / 1000.0
            + " p99_us="
            + p99 / 1000.0
            + " java="
            + System.getProperty("java.version")
            + " cpus="
            + Runtime.getRuntime().availableProcessors()
            + " measured_at="
            + Clock.systemUTC().instant()
            + "\n";
    Files.writeString(Path.of("target", "policy-benchmark.txt"), report, StandardCharsets.UTF_8);
    assertThat(median).isLessThan(1_000_000L);
  }
}
