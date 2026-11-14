// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.audit;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Checks a log export without trusting the gateway that wrote it.
 *
 * <p>It detects an edited entry (its hash no longer matches), a deleted, inserted or reordered
 * entry (a gap in `seq` or a broken `prev_hash` link), a changed timestamp (the hash covers it, and
 * times must not go backwards), and, given checkpoints kept elsewhere, a truncated tail.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public final class ChainVerifier {

  /**
   * One thing wrong with the log.
   *
   * @param seq the entry or checkpoint it concerns
   * @param kind a stable kind, such as `HASH_MISMATCH` or `TRUNCATED`
   * @param detail a sentence for people
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Problem(long seq, String kind, String detail) {}

  /**
   * The outcome of a verification.
   *
   * @param entries how many entries were read
   * @param lastSeq the last sequence number, or 0 for an empty log
   * @param checkpoints how many checkpoints were checked
   * @param problems everything wrong, in order of discovery
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Report(long entries, long lastSeq, long checkpoints, List<Problem> problems) {

    /**
     * Tells whether the log is intact.
     *
     * @return true when nothing is wrong
     * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
     */
    public boolean intact() {
      return problems.isEmpty();
    }
  }

  /**
   * Prevents instances: the verifier is a function.
   *
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private ChainVerifier() {}

  /**
   * Verifies one tenant's entries, in export order, and its checkpoints.
   *
   * @param entries the entries as exported
   * @param checkpoints the checkpoints, kept outside the database if possible
   * @param key the public key, to check checkpoint signatures; empty to skip them
   * @return the report
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static Report verify(
      List<JsonNode> entries, List<JsonNode> checkpoints, Optional<PublicKey> key) {
    List<Problem> problems = new ArrayList<>();
    Map<Long, String> hashes = new HashMap<>();
    String previousHash = HashChain.GENESIS;
    String previousTs = "";
    long expectedSeq = 1;
    for (JsonNode entry : entries) {
      long seq = entry.path("seq").asLong(-1);
      if (seq != expectedSeq) {
        problems.add(
            new Problem(seq, "SEQUENCE_GAP", "expected seq " + expectedSeq + ", found " + seq));
      }
      if (!previousHash.equals(entry.path("prev_hash").asString())) {
        problems.add(
            new Problem(seq, "BROKEN_LINK", "prev_hash does not match the previous entry"));
      }
      String actual = recompute(entry, problems, seq);
      if (!actual.equals(entry.path("hash").asString())) {
        problems.add(
            new Problem(seq, "HASH_MISMATCH", "the entry does not hash to its stored hash"));
      }
      String ts = entry.path("ts").asString();
      if (ts.compareTo(previousTs) < 0) {
        problems.add(new Problem(seq, "TIME_REVERSED", "ts is earlier than the previous entry's"));
      }
      hashes.put(seq, entry.path("hash").asString());
      previousHash = entry.path("hash").asString();
      previousTs = ts;
      expectedSeq = seq + 1;
    }
    long lastSeq = expectedSeq - 1;
    for (JsonNode checkpoint : checkpoints) {
      long seq = checkpoint.path("seq").asLong();
      if (key.isPresent() && !Checkpoints.verify(key.get(), checkpoint)) {
        problems.add(new Problem(seq, "BAD_SIGNATURE", "the checkpoint signature does not verify"));
      }
      if (seq > lastSeq) {
        problems.add(
            new Problem(
                seq,
                "TRUNCATED",
                "a checkpoint covers seq " + seq + " but the log ends at " + lastSeq));
      } else if (!checkpoint.path("hash").asString().equals(hashes.get(seq))) {
        problems.add(
            new Problem(
                seq, "CHECKPOINT_MISMATCH", "the entry at this seq differs from the checkpoint"));
      }
    }
    return new Report(entries.size(), lastSeq, checkpoints.size(), List.copyOf(problems));
  }

  /**
   * Recomputes an entry's hash, reporting a malformed entry instead of failing.
   *
   * @param entry the entry
   * @param problems collects problems
   * @param seq the entry's seq, for the report
   * @return the recomputed hash, or an empty string if it cannot be computed
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static String recompute(JsonNode entry, List<Problem> problems, long seq) {
    try {
      return HashChain.hash(entry);
    } catch (IllegalArgumentException | ClassCastException exception) {
      problems.add(new Problem(seq, "MALFORMED", exception.getMessage()));
      return "";
    }
  }
}
