# Progress

| Phase | Name | State |
|---|---|---|
| 0 | Foundations | done |
| 1 | Demo environment | done |
| 2 | Transparent proxy | done |
| 3 | Identity and catalog | done |
| 4 | Policy engine | done |
| 5 | Verifiable log | done |
| 6 | Approvals | done |
| 7 | Redaction, injection flags and taint | done |
| 8 | Limits, idempotency and stdio servers | done |
| 9 | Console | done |
| 10 | Simulation and replay | not started |
| 11 | Attack bench, performance and reports | not started |
| 12 | Packaging, documentation and publication | not started |

## Phase 0: Foundations

Done on branch `phase-00-foundations`, merged into `main`.

- Git identity, `.gitattributes`, `.editorconfig`, `.gitignore`, `LICENSE`, `NOTICE`, `SECURITY.md`, `CONTRIBUTING.md`.
- Authorship checker, test first: `7cdeb69`, `7431ec5`.
- Commit message and author checker, test first: `5e39e7c`, `0be2454`.
- Licence gate, test first: `5d33daa`, `6b96d87`.
- Maven reactor with four modules: `008d74a`. Stack, Makefile, lint image, pinned images: `da1ea78`.
- CI, CLAUDE.md, ADR 1, 2, 3, 4, 7, comparison, evidence: see the `docs:` and `ci:` commits of the phase.

Acceptance criteria (evidence in `docs/evidence/phase-00.md`):

| CA | Result |
|---|---|
| CA0.1 `make up && make smoke` exits 0 | met (run through a containerised make; the host has no make) |
| CA0.2 lint fails without an author comment | met (`scripts/test_check_authorship.py`, manual run) |
| CA0.3 lint fails with an AGPL dependency | met (itextpdf 5.5.13 added and reverted) |
| CA0.4 a real-format key is refused by the hook | met (random `ghp_` token) |
| CA0.5 ADR 1 lists versions and protocol facts with URL and date | met |
| CA0.6 `docs/comparison.md` cites a source per claim | met |
| CI green | not verified: nothing pushed (pushing needs the owner) |

## Phase 1: Demo environment

Done on branch `phase-01-demo`, merged into `main`.

- TypeScript project with the official MCP SDK 2.4.0: `489ae78`. Dependencies recorded in ADR 16.
- Contract tests for the three servers, the agent and the synthetic data, written first: `f77e29b`.
- `crm`, `payments`, `webfetch` servers: `e97088b`. Scripted agent and two scenarios: `f370a2b`.
- Demo image and compose services, `make test-demo`, `make demo-direct`: `447b257`. `demo/README.md`: `fab624a`.

| CA | Result |
|---|---|
| CA1.1 the three servers answer `server/discover`, `tools/list`, `tools/call` per 2026-07-28 | met: `demo/test/contract.test.ts`, 11 tests, raw JSON-RPC with the required headers and `_meta` |
| CA1.2 `demo/agent` runs a scenario straight against the servers and every step passes | met: `demo/test/scenarios.test.ts` and `make demo-direct` on the running stack (9 of 9 steps) |
| CA1.3 no data looks real | met: `example.com` mail, `XX` IBANs, `000` phones; enforced by three tests; documented in `demo/README.md` |

## Phase 2: Transparent proxy

Done on branch `phase-02-proxy`, merged into `main`.

- Tests first: `ProxyEndpointTest` (15 cases), `ToolNameTest`, fake upstream and raw MCP test client: `f46785f`.
- Checkstyle 14 so `switch` guards parse: `c48da3f`.
- `POST /mcp` with the 2026-07-28 envelope, `server/discover`, `tools/list`, `tools/call`, 2025-11-25
  `initialize`, upstream client (JSON and SSE answers, cancellation on timeout), `<server>__<tool>`
  names, size limits, error codes: `c23f1b3`.
- Upstreams configured by file (`deploy/upstreams.demo.yaml`), `make e2e`, CI step: `a7d3c73`. ADR 17: `e2fcd43`.

| CA | Result |
|---|---|
| CA2.1 the Phase 1 scenarios pass unchanged through Aduana | met: `make e2e`, 9 of 9 steps in 2026-07-28 mode and 9 of 9 with the SDK's 2025-11-25 handshake |
| CA2.2 down upstream gives `UPSTREAM_ERROR`, slow one `UPSTREAM_TIMEOUT`, no hanging threads | met: `an_upstream_that_is_down_gives_upstream_error`, `a_slow_upstream_gives_upstream_timeout_and_leaves_nothing_in_flight` (10 parallel calls to a 5 s upstream with a 400 ms timeout return within 3 s and leave 0 requests in flight) |
| CA2.3 arguments over N bytes are refused before forwarding | met: `oversized_arguments_are_refused_before_anything_is_forwarded` (upstream call count unchanged) |

## Phase 3: Identity and catalog

Done on branch `phase-03-identity-catalog`, merged into `main`.

- Tests first (`IdentityAndCatalogTest` 10, `RouteAccessTest` 3, `CatalogUnitTest` 6; Phase 2 tests now
  authenticate and run on PostgreSQL through Testcontainers): `54fe46b`.
- Dependencies (JDBC, Flyway, Spring Security, OAuth2 resource server, json-schema-validator, BouncyCastle)
  and migration `V1__identity_and_catalog.sql`: `8f61677`.
- API keys, optional OIDC JWT, console users with Argon2id, sessions with CSRF, catalog with pinned
  definitions and review states, AES-GCM credentials injected towards upstreams, JSON Schema validation,
  REST API with `RouteAccess`: `9de2ebd`. DNS re-resolution fix: `ce29fe3`.
- Demo seeding through the API and authenticated e2e: `7b75ab2`. ADR 18: `313349a`.

| CA | Result |
|---|---|
| CA3.1 no credential gives 401 and an entry | met: `a_call_without_a_credential_is_401_and_recorded` (event `AUTH_FAILED` in `event_log`) |
| CA3.2 "does not exist" and "no access" are indistinguishable | met: `a_missing_tool_and_a_disabled_tool_look_the_same_in_body_and_time` (identical bodies; medians of 40 calls differ by less than 10 ms) |
| CA3.3 a changed description leaves `tools/list` until an admin reviews it | met: `a_changed_description_hides_the_tool_until_an_administrator_accepts_it` (fake upstream; the demo servers' descriptions are fixed) |
| CA3.4 the upstream credential never appears in logs, responses or the database in clear | met: `the_upstream_credential_never_appears_in_responses_logs_or_the_database` (captured output, API bodies, every table dumped as text) |
| CA3.5 a REST route without a `RouteAccess` row fails a test | met: `every_declared_route_has_a_row_in_the_access_table` |
| e2e with keys | met: `make e2e` passes 18 of 18 steps with a seeded agent key |

Not covered by a test yet: the optional OIDC JWT path (compiled and wired, no JWT issued in tests).

## Phase 4: Policy engine

Done on branch `phase-04-policy`, merged into `main`.

- `policy` module, tests first: semantics (26), parser (12), tools (6), properties (2 × 1 000 cases),
  architecture (1): `68b1d82`; build gates (JaCoCo 95 %, PIT 80 %): `ba46c10`; model, YAML parser with
  JSON Schema, evaluator, bounded regex, type check, test runner: `1544527`.
- `aduana policy validate|test`: `22faa3c`, `8217014`. Demo bundle (13 rules, 45 cases): `289b4e0`.
- Gateway step 6 and `tools/list` filtering, `V2__policy_bundle.sql`, `GET/POST /api/v1/policy`, bundle
  directory at start-up: `fb51d7a`, `02c0754` (and fix `ec82016`). Stack and gateway scenarios: `2e7d513`.
- `docs/policy-reference.md`, ADR 5: `2221ad1`.

| CA | Result |
|---|---|
| CA4.1 each point of §5.2 has a named test | met: `PolicySemanticsTest` (every rule evaluated, precedence, no match, obligations union, each comparator, paths and `[*]`/`any`, type mismatch, purity), `PolicyParserTest` (schema, duplicate ids, `defaults.decision`) |
| CA4.2 adding a deny never relaxes a decision (1 000 cases) | met: `PolicyPropertiesTest.adding_a_deny_rule_never_relaxes_a_decision`, jqwik, 1 000 tries |
| CA4.3 an invalid policy does not replace the active one | met: `PolicyPipelineTest.an_invalid_or_failing_bundle_never_replaces_the_active_one` (schema error, failing test case, type error; active hash unchanged) |
| CA4.4 coverage ≥ 95 %, mutation ≥ 80 % in `policy` | met: 96.63 % lines (JaCoCo check in `verify`), 254 of 272 mutants killed = 93 % (`make mutation`) |
| CA4.5 median under 1 ms with 200 rules | met: median 4.4 µs, p99 13.2 µs (`make bench-policy`, 20 000 samples, 12 CPUs, Java 25.0.5) |
| demo bundle ≥ 12 rules and ≥ 40 cases | met: 13 rules, 45 cases, `make policy-test` 45 passed |
| e2e | met: `make e2e` 20 of 20 steps in both eras; the injected refund is denied and never reaches payments |

## Phase 5: Verifiable log

Done on branch `phase-05-audit`, merged into `main`.

- `audit` module, tests first (RFC 8785 vectors, chain, checkpoints, six manipulations, property): `34977b3`,
  code (`Jcs`, `HashChain`, `Checkpoints`, `ChainVerifier`): `e74d133`.
- Gateway, tests first (`AuditLogTest`, 9 cases): `b885870`; `V3__audit_log.sql` (tables, role
  `aduana_app`, append-only triggers), two pools, chained `EventLog`, `CALL_DECIDED` before forwarding,
  `CALL_COMPLETED`/`CALL_FAILED`, signed checkpoints, `/api/v1/audit*`, `/.well-known/aduana-audit-key`: `fc0cbe1`.
- `aduana audit verify|export`: `b4c5d02`. `verifier/verify_chain.py` (151 lines, stdlib only),
  `verify_signatures.py` (optional `cryptography`), tests on a real export: `23304f1`.
- `make audit-export`, `make audit-verify`, `make test-verifier`: `f4861a4`. `docs/audit-format.md`, ADR 6, 8, 11: `65a07a5`.

| CA | Result |
|---|---|
| CA5.1 RFC 8785 vectors pass | met: `JcsTest` (24 Appendix B numbers, NaN/Infinity, §3.2.3 sorting, §3.2.4 bytes) and the same vectors in `test_verify_chain.py` |
| CA5.2 six manipulations detected by both verifiers | met: Java `ChainVerifierTest` and Python `ChainTests` (edit, delete, reorder, insert, date change, truncation with exported checkpoints), plus a property test over 300 random edits |
| CA5.3 50 threads, no gaps, no forks | met: `fifty_threads_appending_leave_no_gap_and_no_fork` (200 appends from 50 threads, distinct seqs, chain verifies) |
| CA5.4 UPDATE/DELETE fail for the application role and for the owner | met: `the_application_role_cannot_update_or_delete_entries`, `even_the_owner_cannot_update_delete_or_truncate_entries` |
| CA5.5 with the log unwritable, nothing reaches the upstream | met: `when_the_log_cannot_be_written_nothing_reaches_the_upstream` (INSERT revoked; `AUDIT_UNAVAILABLE`; upstream call count unchanged). A fully stopped database is not simulated |
| CA5.6 verifier under 200 lines, stdlib only | met: `test_the_verifier_is_short_and_uses_only_the_standard_library` (151 lines) |
| stack | met: `make audit-verify` on the demo stack: 84 entries, 2 checkpoints, intact in both verifiers, signatures checked |

Coverage and mutation of `audit`: 95.91 % lines; PIT 91 of 100 mutants killed (91 %).

## Phase 6: Approvals

Done on branch `phase-06-approvals`, merged into `main`.

- Tests first (`ApprovalTest`, 8 cases): `4b86e96`.
- `V4__approval.sql`, `approval/ApprovalService` (binding hash over tenant, principal, tool, arguments
  hash and bundle hash; reuse, hold with database re-checks, atomic consumption inside the same
  transaction as the `CALL_DECIDED` entry, expiry job, supersession on policy load),
  `/api/v1/approvals*`, `/api/v1/users`, `/api/v1/events` (SSE): `aa630c9`.
- Demo approvers and the approve-and-retry round in `make e2e`: `fefecf4`.

| CA | Result |
|---|---|
| CA6.1 an approval consumed by 20 parallel calls executes once | met: `twenty_parallel_retries_of_an_approved_call_execute_exactly_once` (1 success, upstream +1) |
| CA6.2 changing one character of the arguments needs a new approval | met: `changing_one_character_of_the_arguments_needs_a_new_approval` (other payee and +1 cent both pending with a new id; the original then executes) |
| CA6.3 an agent cannot approve, even with a valid key | met: `an_agent_key_cannot_approve_and_a_user_outside_the_group_is_refused` (agent key refused on the API, user outside the group gets 403) |
| CA6.4 an expired approval does not execute and is recorded | met: `an_expired_approval_does_not_execute_and_its_expiry_is_recorded` (`APPROVAL_EXPIRED` entry) |
| CA6.5 a new policy invalidates pending approvals of the old bundle | met: `loading_a_new_policy_invalidates_the_pending_approvals_of_the_old_one` (409 on approve, `APPROVAL_EXPIRED` with `POLICY_CHANGED`) |
| hold mode | met: `a_call_is_held_and_goes_ahead_when_finance_approves_during_the_hold` |
| e2e | met: `make e2e` approves the held refund as `finance@example.com`; the retry executes once and the next identical retry needs a new approval, in both protocol eras |

## Phase 7: Redaction, injection marks and untrusted data

Done on branch `phase-07-inspection`, merged into `main`.

- Tests first (`DetectorsTest` 6, `InspectionTest` 8, labelled set of 120 texts): `e604683`.
- `inspection/` (detectors with validity checks, redactor with opaque and hinted styles, injection
  scanner over `injection-patterns.yaml` v1, traceable values, taint service), `V5__inspection.sql`,
  obligations applied in the pipeline, description marks at sync: `1b28d71`.
- `injected-small-refund.yaml` in `make e2e`, `scripts/check_log_secrets.py` in `make audit-verify`: `d2f5db6`.
- `docs/detectors.md` (measured table), ADR 10, ADR 19, `docs/audit-format.md` flags: `45114fe`.

| CA | Result |
|---|---|
| CA7.1 each detector has positives and near negatives; precision and recall published | met: `docs/detectors.md`, recomputed by `the_published_precision_and_recall_are_the_measured_ones`; all recall 1.00, precision 1.00 except `phone_es` 0.91 (one false alarm, explained) |
| CA7.2 an attacker IBAN from `fetch_url` used in `refund` goes to approval with `FLOW_FROM_UNTRUSTED` and the source `seq` | met: `an_iban_copied_from_an_untrusted_page_into_a_payment_needs_approval_and_says_where_it_came_from`, and `make e2e` (`injected-small-refund.yaml`: refund of 4 999 cents → `APPROVAL_PENDING`; entry flags `FLOW_FROM_UNTRUSTED`, `TAINT_SOURCE:$.destination_iban:seq=25`) |
| CA7.3 the log never holds a sensitive value in clear | met: `the_log_never_holds_a_sensitive_value_in_clear`, and `make audit-verify` on a fresh stack after `make e2e`: `check_log_secrets: 72 entries, 0 values in clear` |
| CA7.4 the database holds no untrusted values in clear, only hashes | met: `the_taint_table_holds_hashes_only` |

## Phase 8: Limits, idempotency and stdio servers

Done on branch `phase-08-limits`, merged into `main`.

- Tests first (`LimitsTest` 8, `StdioTest` 5, `FakeStdioServer`, `demo/test/stdio.test.ts` 3): `55f3bcd`.
- `limits/LimitService` and `limits/IdempotencyService`, `V6__limits_and_idempotency.sql`, result size
  limit, `upstream/StdioConnector`, `notes` demo server over stdio, 85 % line coverage gate on the
  gateway: `f0df97b`.
- ADR 9, `docs/audit-format.md` (limit and idempotency entries), evidence.

| CA | Result |
|---|---|
| CA8.1 going over the rate returns `RATE_LIMITED` with when to retry; counters survive a restart | met: `going_over_the_rate_answers_rate_limited_with_when_to_retry`, `the_counters_are_in_the_database_so_a_restarted_service_still_refuses`, and on the stack: 65 calls → 58 ok, 7 `RATE_LIMITED` with `retry_after_ms`; after `docker compose restart gateway` the bucket still holds 65 and the next call is refused |
| CA8.2 same key and arguments execute once; same key, other arguments → `IDEMPOTENCY_CONFLICT` | met: `the_same_key_and_arguments_run_once_and_replay_the_stored_result`, `ten_parallel_calls_with_one_key_reach_the_upstream_once`, `the_same_key_with_other_arguments_is_an_idempotency_conflict`; on the stack three calls with one key give one `CALL_DECIDED` and two `IDEMPOTENT_REPLAY` |
| CA8.3 a stdio command off the allow-list is not launched | met: `a_command_off_the_allow_list_is_never_launched` (`/usr/bin/touch` never creates its file; its tools never reach the catalog) |
| CA8.4 a dead child is restarted or fails cleanly, never hangs | met: `a_child_that_dies_fails_the_call_and_the_next_call_starts_it_again` (`UPSTREAM_ERROR` in under 2 s, one relaunch), `a_child_that_hangs_times_out_is_stopped_and_started_again` (`UPSTREAM_TIMEOUT` at the 2 s timeout, one relaunch) |
| budget, result size, clean environment | met: `a_principal_that_spends_its_daily_budget_gets_budget_exceeded`, `a_result_over_the_size_limit_is_not_passed_on`, `the_child_gets_a_clean_environment` (`LANG,PATH`) |

Not done: memory limit for stdio children (not available portably from Java; documented in
`docs/HANDOFF.md`), credentials for stdio servers, the `notes` server behind the gateway in compose.

## Phase 9: Console

Done on branch `phase-09-console`, merged into `main`.

- Gateway, tests first (`ConsoleServingTest` 3): `68ddafc`; static console files, header filter with the
  content security policy, last checkpoint in `/api/v1/audit/verify`: `629f9bc`.
- Console tests (Vitest specs, Playwright flows, axe), `ApprovalTest` queue filter: `d5be67c`.
- `console/` (Angular 22, six screens and sign-in), console stage in the `Dockerfile`, SSE opens at once,
  queue filtered by group, `make test-console`, `make demo`, `make console-e2e`, screenshots, ADR 20: `0d7cd60`.

| CA | Result |
|---|---|
| CA9.1 five Playwright flows: sign in, approve, reject, review a definition change, search the log | met: `make console-e2e`, flows 1 to 5 |
| CA9.2 no tokens in `localStorage`; CSP allowing only its own origin | met: flow 1 (storage empty, session cookie `HttpOnly`), `ConsoleServingTest`, and the CSP flow (header without `unsafe-inline`, no CSP violation in the browser console on six screens) |
| CA9.3 axe-core (licence checked) without critical errors on the six screens | met: no `critical` or `serious` violation in light and dark; axe-core is MPL-2.0 (ADR 20) |
| CA9.4 a `viewer` sees no approve buttons and the API answers 403 by hand | met: viewer flow (no Approvals link, guard returns to Activity, POST approve → 403) |
| CA9.5 real screenshots in `docs/images/` with `make demo` data | met: six PNGs from `make console-e2e` after `make demo` |
