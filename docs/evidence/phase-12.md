# Phase 12 evidence

Run on 2026-11-15 on this machine (AMD Ryzen 5 5500, Docker Desktop on Windows).

## CA12.1 quick start

A fresh `git clone` of the branch into an empty folder, with `COMPOSE_PROJECT_NAME=aduanaqs` so no
container, network or volume of the existing stack was reused:

```
make up     16 s   (images built from Docker's layer cache)
make demo   15 s   all steps passed; console at http://localhost:8780 answers 200
```

The same two images built with a new BuildKit builder that had no cache at all (base images, Maven and
npm downloads included): gateway 57 s, demo servers 18 s. Base images Compose pulls by itself
(PostgreSQL, Python) were already on this machine and were not pulled again; that time is not
measured. A first run therefore stays well under the 10-minute target on this machine; a different
clean machine was not available.

## CA12.2 README

Plan §11, point by point: title and three badges (static: nothing is pushed yet); the product sentence
and the principle linked to ADR 2; three links (replay, case study, ADRs); one real screenshot
(`console-approvals-untrusted.png`, the refund held for an untrusted flow); quick start with three
commands and an "open / what you see" table; 8 bullets of what it does; the measured table, every
number backed by a generated report (`check_readme_numbers: 11 claims, 0 unbacked`); what it does not
do; a Mermaid diagram and the 12 steps; a rule, a test case and the command; the two verification
commands; the risk table, one row per attack category; the comparison with its date; the layout; the
status; commercial use and support; the licence line. 198 lines. Missing: the LinkedIn link (not in the
repository).

## CA12.3 release check

```
$ make release-check     rc=0, 1055 s
  lint                   passed (check_licenses: 104 Maven lines, 2 npm lock files, 0 problems; gitleaks over history: no leaks)
  test                   passed (scripts, Java with coverage gates, demo, console 22 tests, verifier)
  up, smoke              gateway healthy
  e2e, demo              all steps passed
  console-e2e            11 passed
  attacks-check          91 cases, 85 stopped; deterministic not stopped: none; report unchanged
  scan                   Trivy: 0 critical or high vulnerabilities with a fix
```

The first run failed at `make demo`, which reused the payments server that `make e2e` had just used;
`make demo` now starts from fresh demo servers. The console flow added afterwards for the README
screenshot brings `make console-e2e` to 12 passed.

## CA12.4 image

```
user=65532:65532                       (distroless nonroot)
docker run --entrypoint /bin/sh …      stat /bin/sh: no such file or directory
HEALTHCHECK                            /usr/bin/java … HealthProbe (exec form)
Trivy 0.70.0 (Apache-2.0)              65 targets, 0 critical or high with a fix
```

The image holds the gateway, the CLI (`java -jar /opt/aduana/aduana-cli.jar`) and the console. Its
arm64 build is defined in `.github/workflows/release.yml` and was not run.

## Production compose

`deploy/compose.prod.yaml` with a generated master key and passwords, run as its own project on port
8781: gateway healthy with a read-only root filesystem, all capabilities dropped, session cookie
`Secure; HttpOnly; SameSite=Strict`. The test project and its volume were removed afterwards.
