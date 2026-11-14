# Progress

| Phase | Name | State |
|---|---|---|
| 0 | Foundations | done |
| 1 | Demo environment | done |
| 2 | Transparent proxy | done |
| 3 | Identity and catalog | done |
| 4 | Policy engine | not started |
| 5 | Verifiable log | not started |
| 6 | Approvals | not started |
| 7 | Redaction, injection flags and taint | not started |
| 8 | Limits, idempotency and stdio servers | not started |
| 9 | Console | not started |
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
