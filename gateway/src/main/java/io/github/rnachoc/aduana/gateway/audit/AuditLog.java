// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Ignacio Rodríguez Caro
package io.github.rnachoc.aduana.gateway.audit;

import io.github.rnachoc.aduana.audit.ChainVerifier;
import io.github.rnachoc.aduana.audit.Checkpoints;
import io.github.rnachoc.aduana.audit.HashChain;
import io.github.rnachoc.aduana.audit.Jcs;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Appends to the hash-chained log, signs checkpoints, and reads, exports and verifies the chain.
 *
 * <p>Each append commits in its own transaction under a per-tenant lock, so an entry is durable
 * before the action it describes happens, and two appends never share a `seq`.
 *
 * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
 */
@Service
public class AuditLog {

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

  private final AuditRepository repository;
  private final SigningKeys keys;
  private final JsonMapper json;
  private final Clock clock;
  private final long checkpointEvery;

  /**
   * Where an entry landed.
   *
   * @param seq its sequence number
   * @param hash its hash
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public record Appended(long seq, String hash) {}

  /**
   * Creates the log.
   *
   * @param repository audit storage
   * @param keys checkpoint keys
   * @param json the shared JSON mapper
   * @param clock the clock
   * @param checkpointEvery sign a checkpoint after this many entries
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public AuditLog(
      AuditRepository repository,
      SigningKeys keys,
      JsonMapper json,
      Clock clock,
      @Value("${aduana.audit.checkpoint-every:100}") long checkpointEvery) {
    this.repository = repository;
    this.keys = keys;
    this.json = json;
    this.clock = clock;
    this.checkpointEvery = checkpointEvery;
  }

  /**
   * Appends an entry, and a signed checkpoint when one is due.
   *
   * @param tenantId the tenant
   * @param record what to record
   * @return the entry's seq and hash
   * @throws org.springframework.dao.DataAccessException if the log cannot be written; callers must
   *     then not perform the action (principle 5)
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Appended append(UUID tenantId, AuditRecord record) {
    repository.lock(tenantId);
    Optional<AuditRepository.Head> head = repository.head(tenantId);
    Appended appended = insert(tenantId, record, head);
    if (appended.seq() % checkpointEvery == 0) {
      checkpointLocked(tenantId, appended);
    }
    return appended;
  }

  /**
   * Signs a checkpoint over the newest entry if entries were added since the last one.
   *
   * @param tenantId the tenant
   * @return the checkpointed seq, if a checkpoint was made
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Long> checkpoint(UUID tenantId) {
    repository.lock(tenantId);
    Optional<AuditRepository.Head> head = repository.head(tenantId);
    long lastCheckpoint = repository.lastCheckpointSeq(tenantId);
    if (head.isEmpty() || head.get().seq() <= lastCheckpoint + 1) {
      return Optional.empty();
    }
    checkpointLocked(tenantId, new Appended(head.get().seq(), head.get().hash()));
    return Optional.of(head.get().seq());
  }

  /**
   * Hashes an argument or result value the way entries reference it.
   *
   * @param value the value
   * @return hex SHA-256 of its canonical form
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public static String hashOf(JsonNode value) {
    return HashChain.sha256Hex(Jcs.canonicalBytes(value));
  }

  /**
   * Reads entries.
   *
   * @param tenantId the tenant
   * @param filter the filters and page
   * @return the entries
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<ObjectNode> entries(UUID tenantId, AuditFilter filter) {
    return repository.entries(tenantId, filter);
  }

  /**
   * Reads every checkpoint.
   *
   * @param tenantId the tenant
   * @return the checkpoints
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public List<ObjectNode> checkpoints(UUID tenantId) {
    return repository.checkpoints(tenantId);
  }

  /**
   * Verifies the whole chain and every checkpoint with the gateway's public key.
   *
   * @param tenantId the tenant
   * @return the report
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  public ChainVerifier.Report verify(UUID tenantId) {
    List<JsonNode> entries = List.copyOf(repository.entries(tenantId, AuditFilter.all()));
    List<JsonNode> checkpoints = List.copyOf(repository.checkpoints(tenantId));
    return ChainVerifier.verify(
        entries, checkpoints, keys.existing(tenantId).map(SigningKeys.Key::publicKey));
  }

  /**
   * Builds, hashes and inserts one entry after the head.
   *
   * @param tenantId the tenant
   * @param record what to record
   * @param head the current newest entry
   * @return the new entry's seq and hash
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private Appended insert(UUID tenantId, AuditRecord record, Optional<AuditRepository.Head> head) {
    long seq = head.map(AuditRepository.Head::seq).orElse(0L) + 1;
    String now = TIMESTAMP.format(clock.instant().truncatedTo(ChronoUnit.MICROS));
    String previousTs = head.map(AuditRepository.Head::ts).orElse("");
    ObjectNode entry = json.createObjectNode();
    entry.put("seq", seq);
    entry.put("tenant_id", tenantId.toString());
    // Times never go backwards in the chain, even if the system clock does.
    entry.put("ts", now.compareTo(previousTs) >= 0 ? now : previousTs);
    entry.put("type", record.type());
    entry.put("principal_id", record.principalId().map(UUID::toString).orElse(null));
    entry.put("server", record.server().orElse(null));
    entry.put("tool", record.tool().orElse(null));
    entry.put("args_hash", record.argsHash().orElse(null));
    entry.set("args_redacted", record.argsRedacted().orElseGet(json::nullNode));
    entry.put("decision", record.decision().orElse(null));
    record.ruleIds().forEach(entry.putArray("rule_ids")::add);
    entry.put("reason_code", record.reasonCode().orElse(null));
    entry.put("policy_bundle_hash", record.policyBundleHash());
    entry.put("approval_id", record.approvalId().map(UUID::toString).orElse(null));
    entry.put("actor_id", record.actorId().map(UUID::toString).orElse(null));
    entry.put("result_hash", record.resultHash().orElse(null));
    entry
        .putArray("flags")
        .addAll(record.flags().stream().map(json.getNodeFactory()::stringNode).toList());
    if (record.latencyMs().isPresent()) {
      entry.put("latency_ms", record.latencyMs().get());
    } else {
      entry.putNull("latency_ms");
    }
    entry.put("prev_hash", head.map(AuditRepository.Head::hash).orElse(HashChain.GENESIS));
    entry.put("hash", HashChain.hash(entry));
    repository.insert(entry);
    return new Appended(seq, entry.get("hash").asString());
  }

  /**
   * Signs a checkpoint over an entry and records that it did, while holding the tenant lock.
   *
   * @param tenantId the tenant
   * @param target the entry the checkpoint covers
   * @author Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
   */
  private void checkpointLocked(UUID tenantId, Appended target) {
    SigningKeys.Key key = keys.key(tenantId);
    String now = TIMESTAMP.format(clock.instant().truncatedTo(ChronoUnit.MICROS));
    ObjectNode checkpoint =
        Checkpoints.sign(
            key.privateKey(), key.keyId(), tenantId.toString(), target.seq(), target.hash(), now);
    repository.insertCheckpoint(checkpoint);
    ObjectNode details = json.createObjectNode();
    details.put("checkpoint_seq", target.seq());
    details.put("key_id", key.keyId());
    AuditRecord record =
        AuditRecord.of("CHECKPOINT").details(details).resultHash(target.hash()).build();
    insert(
        tenantId, record, Optional.of(new AuditRepository.Head(target.seq(), target.hash(), now)));
  }
}
