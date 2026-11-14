# Handoff

Rewritten at the end of every session. Last update: 2026-11-14, end of Phase 0.

## What exists

- Maven reactor (`pom.xml`) with `policy`, `audit`, `gateway`, `cli`. Each compiles and has one test.
  `gateway` is a Spring Boot 4.1.2 app with `GET /healthz` and a `HealthProbe` main class used by the
  container `HEALTHCHECK` (the JRE image has no curl).
- `Dockerfile` (multi-stage, non-root uid 10001), `compose.yaml` (PostgreSQL 18.7 + gateway on
  `127.0.0.1:8780` by default; 8080 was taken on the owner's machine), `.env.example`.
- `Makefile`: `help up down logs smoke lint lint-fast secrets test test-scripts test-java test-policy
  test-audit test-gateway hooks`. All tools run in containers pinned by digest (`scripts/images.env`).
- Checks, each with unittest tests in `scripts/test_*.py`:
  `check_authorship.py`, `check_commits.py` (message rules and owner identity over `HEAD`),
  `check_licenses.py` + `check_licenses.sh`, `check_pinned_images.py`, `smoke.py`.
- Hooks in `.githooks/` (`pre-commit`: gitleaks on the staged diff + authorship of staged files;
  `commit-msg`: message rules). `core.hooksPath` is set in this clone.
- CI workflow `.github/workflows/ci.yml` (lint, test, stack + smoke). Not run yet: nothing is pushed.
- ADR 1, 2, 3, 4, 7; `docs/comparison.md`; `docs/evidence/phase-00.md`.

## What is left

Phases 1 to 12 of `docs/PLAN.md` §10. Next is Phase 1: three demo MCP servers in TypeScript with the
official SDK 2.4.0 (`@modelcontextprotocol/server`), a scripted agent and scenarios.

## How to do the next pieces

- Phase 1: put the servers under `demo/servers/{crm,payments,webfetch}` with one shared `package.json`
  in `demo/` and a lock file (the licence gate reads `package-lock.json`). Run `npm` only inside the
  pinned Node image. Add a `demo/Dockerfile` and compose services for the three servers.
- Add `make` targets as phases need them (`demo`, `e2e`, …) and list them in `make help`.

## Known traps

- This Windows machine has no GNU make. To run `make` here, run it from a container: `docker:29-cli`
  with `make git python3` added, the Docker socket mounted, and the repository mounted at
  `/run/desktop/mnt/host/c/Users/Nacho/Documents/AduanAI` (the path Docker Desktop maps to the host),
  working directory the same path. Bind mounts made by the Makefile then resolve on the host.
- Git Bash rewrites paths like `/src`: the Makefile exports `MSYS_NO_PATHCONV=1`; scripts do the same.
- Files written by Python on Windows get CRLF unless written with `newline="\n"`; editorconfig-checker fails on CRLF.
- The global git identity on this machine is a placeholder. The repository sets its own identity;
  `make lint` checks every commit of `HEAD`.
- Ruff in `build-config/lint` uses `ruff.toml` at the root; EXE rules are off because Windows marks files executable.
