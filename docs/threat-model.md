# Threat model

STRIDE per component of the gateway, as built at the end of Phase 11. Each threat names what mitigates
it and, where it applies, the attack bench cases in [`attacks/cases/`](../attacks/cases/) that exercise
it ([`attacks/REPORT.md`](../attacks/REPORT.md) has the results). "Residual" is what stays open.

Assets: the upstream tools' side effects (refunds, e-mail changes), personal data in arguments and
results, the upstream credentials, the policy, the approvals, and the log as evidence.

Trust boundaries: agent → gateway (`/mcp`), console user → gateway (`/api/v1`), gateway → upstream
servers (HTTP or stdio child), gateway → PostgreSQL, exported log → third-party verifier.

## Agent endpoint (`POST /mcp`)

| STRIDE | Threat | Mitigation | Cases |
|---|---|---|---|
| Spoofing | Calling without a key, with a guessed, revoked or forged key, or with an unsigned JWT | API keys stored as SHA-256 of the whole secret; revocation checked on every call; JWTs only when an OIDC issuer is configured | DEP-01, DEP-02, DEP-03, DEP-07 |
| Spoofing | Claiming another team or identity in `_meta` or headers | Attributes come only from the principal record | DEP-04, DEP-05 |
| Tampering | Smuggling data or extra fields through arguments | Strict JSON Schema validation against the pinned definition; size limits | EXF-09, RES-02, RES-03 |
| Repudiation | An agent denies having made a call | Every decision is logged before forwarding, with the principal, the arguments' hash and the bundle hash | LOG-* |
| Information disclosure | Learning which tools exist by probing | Tools the policy never offers are hidden and answer like missing tools | ESC-01 to ESC-04 |
| Denial of service | Floods, loops, oversized or deeply nested bodies, batches | Per-tool and per-principal rates, daily budget, body and argument limits, no JSON-RPC batches | RES-01 to RES-09 |
| Elevation of privilege | Calling tools outside the catalog or not yet allowed | `<server>__<tool>` namespacing, catalog lookup, default deny | ESC-01 to ESC-06, TDP-04, TDP-06 |

Residual: an agent whose key leaks is that agent until the key is revoked. Rate limits count per
principal, so many stolen keys multiply the allowance.

## Policy engine and inspection

| STRIDE | Threat | Mitigation | Cases |
|---|---|---|---|
| Tampering | A page or record instructs the agent to act (prompt injection through results) | Deterministic rules on context (`context_tainted`), amounts, accounts and teams; approval for flows from untrusted data; injection marks on results | INJ-01 to INJ-12 |
| Information disclosure | Personal data and secrets leaking out through arguments | `deny_args_containing` obligations, rules on destinations, redaction of results | EXF-01 to EXF-10 |
| Information disclosure | Sensitive values stored in clear in the log | Every argument string is redacted before logging, with hints for approvers (ADR 19) | RED-02, RED-03 |
| Elevation of privilege | A tool description poisoned at registration or changed later | Description marks at sync; changed definitions taken out of service until reviewed; default deny for unreviewed servers | TDP-01 to TDP-06 |

Residual, measured by the bench as heuristic cases that escape:

- Taint by literal value is scoped to the trace id of the read, and the agent chooses its trace id:
  reading under one trace and acting under another escapes `FLOW_FROM_UNTRUSTED` (INJ-04). The
  context-taint rule, which covers every trace, still applies.
- Detectors match plain text: zero-width characters, full-width digits, base64 and values split across
  calls escape them (RED-04 to RED-07).
- Injection patterns are English phrases: other languages and paraphrases escape (TDP-05).
- Injection marks never block; a model may still follow an instruction it was warned about. The
  deterministic rules are what stop the action.

## Approvals

| STRIDE | Threat | Mitigation | Cases |
|---|---|---|---|
| Spoofing | The agent approves its own call, or a viewer or another group does | Console sessions only; approver role; group named by the rule | APR-05, APR-06, APR-07, INJ-12 |
| Tampering | One approval stretched over other arguments, another agent or repeated calls | Binding hash over tenant, principal, tool, arguments and bundle; consumed in the same transaction that logs the call | APR-01 to APR-04, APR-10 |
| Tampering | A forged approval id in the request | Approvals are found by binding, never taken from the request | APR-09 |
| Repudiation | An approver denies a decision | `APPROVAL_DECIDED` entries with the actor | |

Residual: no separation of duties in v1 — whoever wrote a rule may approve under it (plan §5.4). An
approver sees redacted arguments with hints; four characters of an IBAN distinguish accounts but a
careless approver can still approve the wrong one.

## Log and database

| STRIDE | Threat | Mitigation | Cases |
|---|---|---|---|
| Tampering | Updating, deleting or truncating entries through the gateway's role | Append-only triggers and grants | LOG-01 to LOG-04 |
| Tampering | Editing, removing, reordering, duplicating or backdating entries in an export | Hash chain verified by the CLI and the stand-alone Python verifier | LOG-05 to LOG-07, LOG-11, LOG-12 |
| Tampering | The database owner rewrites history and rehashes the chain | Ed25519-signed checkpoints, checked against copies kept outside the database | LOG-09, LOG-10 |
| Tampering | Cutting the end of an export | Signed checkpoints cover the entries before them | LOG-08 |
| Denial of service | The log cannot be written | Fail closed: nothing is forwarded (`AUDIT_UNAVAILABLE`, ADR 8) | |
| Repudiation | Approvals or calls without a trace | Every call, refusal and administration change is an entry | |

Residual:

- Entries written after the newest checkpoint can be cut from an export, or rewritten by the database
  owner, without detection until the next checkpoint (every 20 entries in the demo, 100 by default).
- Checkpoints only help if someone keeps copies outside the database; the export is manual.
- The signing key lives in the database, encrypted with the master key; an attacker with both can sign
  forged checkpoints.

## Upstream connectors

| STRIDE | Threat | Mitigation | Cases |
|---|---|---|---|
| Information disclosure | The agent obtains an upstream credential | Credentials are encrypted at rest (AES-GCM) and injected by the gateway; the agent's own `Authorization` is never forwarded | |
| Elevation of privilege | Launching an arbitrary command as a stdio server | Allow-list of command paths; clean environment | `StdioTest` (CA8.3) |
| Denial of service | A hung or dying upstream ties up the gateway | Per-server timeouts; stdio children killed and restarted with backoff | `StdioTest` (CA8.4) |

Residual: no memory limit on stdio children; a result the upstream signs as trustworthy is still only
as trustworthy as that upstream.

## Console and administration API

| STRIDE | Threat | Mitigation | Cases |
|---|---|---|---|
| Spoofing | An agent key or anonymous request on `/api/v1` | Session cookie only; route table with a role per route | ADM-01, ADM-02 |
| Tampering | Cross-site request forgery | Double-submit CSRF token on every write; `SameSite=Strict` cookie | ADM-04 |
| Elevation of privilege | Lower roles reaching admin routes | Route table; a test fails if a route has no row | ADM-03, ADM-05, ADM-07 |
| Information disclosure | Framework endpoints, script injection, stolen tokens | Everything outside the table is denied; content security policy allowing only the gateway's own origin; nothing in browser storage | ADM-06, `make console-e2e` |

Residual: console users authenticate with passwords (Argon2id) and no second factor in v1; sessions
last 8 hours.

## Deployment

The demo uses `local-only` secrets from `.env.example` and plain HTTP inside Docker's network. A real
deployment needs its own master key, TLS in front of the gateway, `ADUANA_SECURE_COOKIES=true`, and the
checkpoints copied somewhere the database owner cannot write. Aduana runs as one node with one
database (plan §2).
