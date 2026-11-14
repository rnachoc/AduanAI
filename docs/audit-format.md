# Audit log format

This document is enough to write a verifier for an Aduana export without reading Aduana's code.
Two exist: `aduana audit verify` (Java, `audit/`) and [`verifier/verify_chain.py`](../verifier/verify_chain.py)
(Python standard library only, under 200 lines).

## Files

`GET /api/v1/audit/export` (administrators) returns the **entries**, one JSON object per line, in `seq`
order. `GET /api/v1/audit/export?checkpoints=true` returns the **checkpoints** the same way.
`GET /.well-known/aduana-audit-key` (public) returns the key that signs checkpoints:

```json
{"algorithm": "Ed25519", "key_id": "f2779980afae8960", "tenant_id": "…",
 "public_key": "<base64 of the raw 32-byte key>", "spki": "<base64 of the X.509 SubjectPublicKeyInfo>"}
```

```sh
make audit-export                                  # writes out/audit/{entries,checkpoints}.jsonl and key.json
aduana audit verify --export entries.jsonl --checkpoints checkpoints.jsonl --key key.json
python verifier/verify_chain.py entries.jsonl --checkpoints checkpoints.jsonl
python verifier/verify_signatures.py checkpoints.jsonl key.json      # needs the cryptography package
```

Keep checkpoint files somewhere the gateway's operators cannot write: they are what proves that the tail
of the log was not cut off.

## Entry

Every entry has all of these fields; absent values are JSON `null`, empty lists are `[]`.

| Field | Type | Meaning |
|---|---|---|
| `seq` | integer | 1, 2, 3… per tenant, with no gaps |
| `tenant_id` | string (UUID) | The tenant |
| `ts` | string | UTC time, `YYYY-MM-DDTHH:MM:SS.ffffffZ` (microseconds); never decreases |
| `type` | string | One of the types below |
| `principal_id` | string (UUID) or null | The agent the entry is about |
| `server`, `tool` | string or null | Server name and agent-facing tool name `<server>__<tool>` |
| `args_hash` | string or null | Hex SHA-256 of the canonical form (below) of the call's original arguments |
| `args_redacted` | object or null | The arguments with every detector's matches replaced by `[REDACTED:<id>:<hint>]` (ADR 19); for administration events, their details |
| `decision` | string or null | `allow`, `require_approval` or `deny` |
| `rule_ids` | array of strings | The policy rules that matched |
| `reason_code` | string or null | Why: a deny's reason, a failure code, an administration action |
| `policy_bundle_hash` | string | Hex SHA-256 of the active policy bundle; 64 zeros when none applies |
| `approval_id` | string (UUID) or null | The approval involved |
| `actor_id` | string (UUID) or null | The console user behind an administration event |
| `result_hash` | string or null | Hex SHA-256 of the canonical tool result; for `CHECKPOINT`, the checkpointed hash |
| `flags` | array of strings | Marks: `POLICY_TYPE_MISMATCH`, `REDACTED`, `SENSITIVE_<DETECTOR>`, `CONTEXT_TAINTED`, `FLOW_FROM_UNTRUSTED`, `TAINT_SOURCE:<argument path>:seq=<seq>`, `INJECTION_PATTERN`, `INJECTION_PATTERN:<pattern>:v<version>` |
| `latency_ms` | integer or null | Time the upstream call took |
| `prev_hash` | string | The previous entry's `hash`; 64 zeros (`000…0`) for `seq` 1 |
| `hash` | string | See below |

Types: `AUTH_FAILED`, `TOOL_NOT_FOUND`, `ARGS_INVALID`, `CALL_DECIDED`, `CALL_COMPLETED`, `CALL_FAILED`,
`APPROVAL_CREATED`, `APPROVAL_DECIDED`, `APPROVAL_EXPIRED`, `RATE_LIMITED`, `IDEMPOTENT_REPLAY`,
`TOOL_DEFINITION_CHANGED`, `POLICY_LOADED`, `POLICY_REJECTED`, `SERVER_CHANGED`, `TOOL_CHANGED`,
`KEY_CREATED`, `KEY_REVOKED`, `CHECKPOINT`.

A call produces `CALL_DECIDED` (written and committed **before** anything is forwarded; if it cannot be
written, the call is refused with `AUDIT_UNAVAILABLE`), then `CALL_COMPLETED` or `CALL_FAILED`.

## Hash

```
hash = lowercase_hex( SHA-256( bytes_of_hex(prev_hash) || UTF-8( JCS( entry without the "hash" field ) ) ) )
```

`bytes_of_hex(prev_hash)` is the 32 raw bytes, not the 64 characters. **JCS** is the JSON Canonicalization
Scheme, [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785):

- Object keys sorted by their UTF-16 code units; no whitespace anywhere.
- Strings: `"` → `\"`, `\` → `\\`, U+0008/09/0A/0C/0D → `\b \t \n \f \r`, other characters below U+0020 →
  `\u00xx` with lowercase hex, everything else (including `/` and non-ASCII) as is. Lone surrogates are
  errors.
- Numbers: as IEEE-754 doubles printed by ECMAScript's `Number.prototype.toString` (`4.50` → `4.5`,
  `1E30` → `1e+30`, `0.000001` stays, `1e-7` → `1e-7`). Integers are doubles too: read them as floats.
- `true`, `false`, `null` as written.

## Checkpoint

```json
{"tenant_id":"…","seq":40,"hash":"<hash of entry 40>","ts":"…","signature":"<base64>","key_id":"…"}
```

`signature` is Ed25519 (RFC 8032) over `UTF-8(JCS({"tenant_id", "seq", "hash", "ts"}))`, with the key
published at `/.well-known/aduana-audit-key`. A checkpoint is signed after every N entries (`aduana.audit.checkpoint-every`,
20 in the demo stack, 100 by default) and every 5 minutes when entries were added. Each checkpoint is
also recorded in the chain as a `CHECKPOINT` entry.

## What a verifier checks

| Check | Detects |
|---|---|
| `seq` runs 1, 2, 3… | deleted, inserted or reordered entries |
| each `prev_hash` equals the previous `hash` | deleted, inserted or reordered entries |
| each `hash` recomputes | any edited field, including `ts` |
| `ts` never decreases | moved dates |
| each checkpoint's `hash` equals the entry at its `seq` | a rewritten chain |
| no checkpoint beyond the last entry | a truncated tail |
| checkpoint signatures | forged checkpoints |

Limits: an export that starts after `seq` 1 cannot be verified on its own (the first link is missing), and
without a checkpoint kept elsewhere, deleting the newest entries is not detectable.
