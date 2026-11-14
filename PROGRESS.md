# Progress

| Phase | Name | State |
|---|---|---|
| 0 | Foundations | done |
| 1 | Demo environment | done |
| 2 | Transparent proxy | not started |
| 3 | Identity and catalog | not started |
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
