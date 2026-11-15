# Aduana

A control gateway for AI agents: every tool call is checked by policy written in code, approved by a person when it matters, and recorded in a log that anyone can verify.

**[The model proposes, the code decides.](docs/adr/0002-the-model-proposes-the-code-decides.md)**

Aduana is under construction. This README grows to the structure in `docs/PLAN.md` §11 as each phase lands;
until then it only states what exists.

## Quick start

Requirements: Docker and GNU make.

```sh
make up      # build and start PostgreSQL and the gateway, wait until healthy
make smoke   # probe the running stack
make lint    # every static check
make test    # every test suite
```

## Status

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

## Licence

Apache-2.0, © 2026 Ignacio Rodríguez Caro.
