// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.cli.attacks;

import io.github.rnachoc.aduana.audit.HashChain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ways an attacker with write access to an exported log (or to the database behind the API) could
 * rewrite history. Each works on copies; the verifier must notice every one.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
public enum Tampering {
  /** Change an entry's redacted arguments and keep its hash. */
  EDIT_ARGUMENTS,
  /** Change an entry's decision and recompute its hash, but not the next entry's link. */
  EDIT_DECISION_AND_REHASH,
  /** Remove one entry from the middle. */
  DELETE_ENTRY,
  /** Swap two neighbouring entries. */
  SWAP_ENTRIES,
  /**
   * Cut the end of the log back past the newest checkpoint. (Entries written after the newest
   * checkpoint can be cut without trace; the threat model says so.)
   */
  TRUNCATE_END,
  /** Rewrite one entry and rehash every entry after it, as the owner of the database could. */
  REWRITE_HISTORY,
  /** Point a checkpoint at another hash and keep its signature. */
  FORGE_CHECKPOINT,
  /** Insert a copy of an entry as if it happened twice. */
  DUPLICATE_ENTRY,
  /** Move an entry's time before the previous entry's and rehash the chain after it. */
  BACKDATE_ENTRY;

  /**
   * The log after tampering.
   *
   * @param entries the entries
   * @param checkpoints the checkpoints
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Tampered(List<JsonNode> entries, List<JsonNode> checkpoints) {}

  /**
   * Applies this tampering to copies of a log of at least four entries.
   *
   * @param entries the entries, in order
   * @param checkpoints the signed checkpoints
   * @return the tampered copies
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public Tampered apply(List<JsonNode> entries, List<JsonNode> checkpoints) {
    List<JsonNode> log = new ArrayList<>();
    entries.forEach(entry -> log.add(entry.deepCopy()));
    List<JsonNode> signed = new ArrayList<>();
    checkpoints.forEach(checkpoint -> signed.add(checkpoint.deepCopy()));
    int middle = log.size() / 2;
    switch (this) {
      case EDIT_ARGUMENTS ->
          ((ObjectNode) log.get(middle)).putObject("args_redacted").put("amount_cents", 1);
      case EDIT_DECISION_AND_REHASH -> {
        ObjectNode entry = (ObjectNode) log.get(middle);
        flipDecision(entry);
        rehash(entry);
      }
      case DELETE_ENTRY -> log.remove(middle);
      case SWAP_ENTRIES -> Collections.swap(log, middle, middle + 1);
      case TRUNCATE_END -> {
        long cut =
            signed.isEmpty()
                ? log.getLast().path("seq").asLong()
                : signed.getLast().path("seq").asLong();
        log.removeIf(entry -> entry.path("seq").asLong() >= cut);
      }
      case REWRITE_HISTORY -> {
        flipDecision((ObjectNode) log.get(1));
        rehashFrom(log, 1);
      }
      case FORGE_CHECKPOINT ->
          ((ObjectNode) signed.getLast()).put("hash", log.get(1).path("hash").asString());
      case DUPLICATE_ENTRY -> log.add(middle, log.get(middle).deepCopy());
      case BACKDATE_ENTRY -> {
        ((ObjectNode) log.get(middle)).put("ts", "2000-01-01T00:00:00Z");
        rehashFrom(log, middle);
      }
      default -> throw new IllegalStateException("unknown tampering " + this);
    }
    return new Tampered(log, signed);
  }

  /**
   * Changes an entry's decision to one it does not have.
   *
   * @param entry the entry
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static void flipDecision(ObjectNode entry) {
    entry.put("decision", "allow".equals(entry.path("decision").asString("")) ? "deny" : "allow");
  }

  /**
   * Recomputes one entry's hash from its own content and `prev_hash`.
   *
   * @param entry the entry
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static void rehash(ObjectNode entry) {
    entry.remove("hash");
    entry.put("hash", HashChain.hash(entry));
  }

  /**
   * Relinks and rehashes every entry from an index to the end.
   *
   * @param log the entries
   * @param from the first index to rehash
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static void rehashFrom(List<JsonNode> log, int from) {
    for (int index = from; index < log.size(); index++) {
      ObjectNode entry = (ObjectNode) log.get(index);
      if (index > 0) {
        entry.put("prev_hash", log.get(index - 1).path("hash").asString());
      }
      rehash(entry);
    }
  }
}
