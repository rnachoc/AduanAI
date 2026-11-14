# Progress

| Phase | Name | State |
|---|---|---|
| 0 | Foundations | done |
| 1 | Demo environment | not started |
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
