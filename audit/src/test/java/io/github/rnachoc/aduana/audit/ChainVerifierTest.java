// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The six manipulations of CA5.2, each detected; and any change to any entry is detected.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
class ChainVerifierTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String TENANT = "00000000-0000-0000-0000-000000000001";
  private static final KeyPair KEYS = Checkpoints.generateKeyPair();

  /**
   * Builds a valid chain of entries.
   *
   * @param size how many entries
   * @return the entries, each hashed onto the previous one
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static List<JsonNode> chain(int size) {
    List<JsonNode> entries = new ArrayList<>();
    String previous = HashChain.GENESIS;
    for (int seq = 1; seq <= size; seq++) {
      ObjectNode entry = JSON.createObjectNode();
      entry.put("seq", seq);
      entry.put("tenant_id", TENANT);
      entry.put("ts", String.format("2026-11-14T10:00:%02d.000000Z", seq % 60));
      entry.put("type", "CALL_DECIDED");
      entry.put("tool", "payments__refund");
      entry.putObject("args_redacted").put("amount_cents", seq * 100);
      entry.put("decision", seq % 2 == 0 ? "allow" : "deny");
      entry.putArray("rule_ids").add("r" + seq);
      entry.putNull("approval_id");
      entry.put("prev_hash", previous);
      entry.put("hash", HashChain.hash(entry));
      previous = entry.get("hash").asString();
      entries.add(entry);
    }
    return entries;
  }

  /**
   * Signs a checkpoint over one entry.
   *
   * @param entry the entry
   * @return the checkpoint
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  static JsonNode checkpoint(JsonNode entry) {
    return Checkpoints.sign(
        KEYS.getPrivate(),
        Checkpoints.keyId(KEYS.getPublic()),
        TENANT,
        entry.path("seq").asLong(),
        entry.path("hash").asString(),
        "2026-11-14T11:00:00.000000Z");
  }

  /**
   * Verifies entries with signature checks.
   *
   * @param entries the entries
   * @param checkpoints the checkpoints
   * @return the problem kinds found
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private static List<String> kinds(List<JsonNode> entries, List<JsonNode> checkpoints) {
    return ChainVerifier.verify(entries, checkpoints, Optional.of(KEYS.getPublic()))
        .problems()
        .stream()
        .map(ChainVerifier.Problem::kind)
        .toList();
  }

  @Test
  void an_intact_log_and_its_checkpoints_verify() {
    List<JsonNode> entries = chain(10);
    ChainVerifier.Report report =
        ChainVerifier.verify(
            entries,
            List.of(checkpoint(entries.get(4)), checkpoint(entries.get(9))),
            Optional.of(KEYS.getPublic()));
    assertThat(report.intact()).isTrue();
    assertThat(report.lastSeq()).isEqualTo(10);
    assertThat(report.checkpoints()).isEqualTo(2);
  }

  @Test
  void an_edited_entry_is_detected() {
    List<JsonNode> entries = chain(5);
    ((ObjectNode) entries.get(2)).put("decision", "allow");
    assertThat(kinds(entries, List.of())).contains("HASH_MISMATCH");
  }

  @Test
  void a_deleted_entry_is_detected() {
    List<JsonNode> entries = chain(5);
    entries.remove(2);
    assertThat(kinds(entries, List.of())).contains("SEQUENCE_GAP", "BROKEN_LINK");
  }

  @Test
  void reordered_entries_are_detected() {
    List<JsonNode> entries = chain(5);
    Collections.swap(entries, 1, 3);
    assertThat(kinds(entries, List.of())).contains("SEQUENCE_GAP", "BROKEN_LINK");
  }

  @Test
  void an_inserted_entry_is_detected_even_with_a_valid_hash_of_its_own() {
    List<JsonNode> entries = chain(5);
    ObjectNode forged = ((ObjectNode) entries.get(1)).deepCopy();
    forged.put("decision", "allow");
    forged.put("hash", HashChain.hash(forged));
    entries.add(2, forged);
    assertThat(kinds(entries, List.of())).contains("SEQUENCE_GAP", "BROKEN_LINK");
  }

  @Test
  void a_changed_date_is_detected() {
    List<JsonNode> entries = chain(5);
    ((ObjectNode) entries.get(3)).put("ts", "2026-11-13T09:00:00.000000Z");
    assertThat(kinds(entries, List.of())).contains("HASH_MISMATCH", "TIME_REVERSED");
  }

  @Test
  void a_truncated_tail_is_detected_with_an_exported_checkpoint() {
    List<JsonNode> entries = chain(8);
    JsonNode last = checkpoint(entries.get(7));
    List<JsonNode> truncated = new ArrayList<>(entries.subList(0, 5));
    assertThat(kinds(truncated, List.of())).isEmpty();
    assertThat(kinds(truncated, List.of(last))).containsExactly("TRUNCATED");
  }

  @Test
  void a_rewritten_chain_does_not_match_its_checkpoint() {
    List<JsonNode> entries = chain(4);
    JsonNode signed = checkpoint(entries.get(3));
    List<JsonNode> rewritten = chain(4);
    ((ObjectNode) rewritten.get(0)).put("decision", "allow");
    String previous = HashChain.GENESIS;
    for (JsonNode entry : rewritten) {
      ((ObjectNode) entry).put("prev_hash", previous);
      ((ObjectNode) entry).put("hash", HashChain.hash(entry));
      previous = entry.get("hash").asString();
    }
    assertThat(kinds(rewritten, List.of(signed))).containsExactly("CHECKPOINT_MISMATCH");
  }

  @Test
  void a_forged_or_altered_checkpoint_fails_its_signature() {
    List<JsonNode> entries = chain(3);
    ObjectNode altered = (ObjectNode) checkpoint(entries.get(2)).deepCopy();
    altered.put("seq", 2);
    altered.put("hash", entries.get(1).path("hash").asString());
    assertThat(kinds(entries, List.of(altered))).containsExactly("BAD_SIGNATURE");
    ObjectNode garbage = ((ObjectNode) checkpoint(entries.get(2))).put("signature", "not base64!");
    assertThat(kinds(entries, List.of(garbage))).containsExactly("BAD_SIGNATURE");
    assertThat(ChainVerifier.verify(entries, List.of(altered), Optional.empty()).intact()).isTrue();
  }

  @Test
  void a_malformed_entry_is_reported_not_thrown() {
    List<JsonNode> entries = chain(2);
    ((ObjectNode) entries.get(1)).put("prev_hash", "nothex");
    assertThat(kinds(entries, List.of())).contains("MALFORMED");
  }

  @Test
  void keys_round_trip_through_their_encodings() {
    assertThat(Checkpoints.publicKey(KEYS.getPublic().getEncoded())).isEqualTo(KEYS.getPublic());
    assertThat(Checkpoints.privateKey(KEYS.getPrivate().getEncoded()).getEncoded())
        .isEqualTo(KEYS.getPrivate().getEncoded());
    assertThat(Checkpoints.rawPublicKey(KEYS.getPublic())).hasSize(32);
    assertThat(Checkpoints.keyId(KEYS.getPublic())).hasSize(16);
    assertThatThrownBy(() -> Checkpoints.publicKey(new byte[] {1, 2}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Checkpoints.privateKey(new byte[] {1, 2}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void the_first_entry_links_to_sixty_four_zeros() {
    assertThat(chain(1).getFirst().path("prev_hash").asString()).isEqualTo("0".repeat(64));
    assertThat(HashChain.sha256Hex(new byte[0]))
        .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
  }

  @Property(tries = 300)
  void any_change_to_any_field_of_any_entry_is_detected(
      @ForAll @IntRange(min = 0, max = 9) int index,
      @ForAll("fields") String field,
      @ForAll @IntRange(min = 1, max = 1000) int value) {
    List<JsonNode> entries = chain(10);
    ObjectNode target = (ObjectNode) entries.get(index);
    JsonNode before = target.get(field);
    if (field.equals("seq")) {
      target.put(field, before.asLong() + value);
    } else {
      target.put(field, before.asString() + "x" + value);
    }
    assertThat(ChainVerifier.verify(entries, List.of(), Optional.empty()).intact()).isFalse();
  }

  /**
   * The fields a property test may change.
   *
   * @return the generator
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @net.jqwik.api.Provide
  net.jqwik.api.Arbitrary<String> fields() {
    return net.jqwik.api.Arbitraries.of(
        "seq", "tenant_id", "ts", "type", "tool", "decision", "prev_hash", "hash");
  }
}
