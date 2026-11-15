# Handoff

Rewritten at the end of every session. Last update: 2026-11-15, end of Phase 12: every phase of `docs/PLAN.md` §10 is done.

## What exists

- **Phase 0**: Maven reactor (`policy`, `audit`, `gateway`, `cli`), Dockerfile, `compose.yaml`, Makefile,
  lint and licence gates, authorship and commit checkers with tests, hooks, CI workflow, ADR 1–4, 7.
- **Phase 1**: `demo/` (TypeScript, MCP SDK 2.4.0): `crm`, `payments`, `webfetch` servers, scripted
  agent (`demo/agent/agent.ts`, pinned to 2026-07-28, `--legacy` for 2025-11-25), scenarios, contract
  tests. ADR 16.
- **Phase 2**: gateway MCP endpoint `POST /mcp` (`web/McpController`, `mcp/` envelope, `service/`
  dispatch), upstream client (`upstream/UpstreamClient`: JSON or SSE answers, cancellation on timeout,
  injected bearer credential), `<server>__<tool>` names, size limits, error codes (`error/`). ADR 17.
- **Phase 3**: PostgreSQL schema `V1__identity_and_catalog.sql`; `identity/` (principals, API keys,
  console users with Argon2id); `catalog/` (servers, pinned tool definitions, review states
  `changed_pending_review` / `new_pending_review` / `removed`, AES-GCM credentials, JSON Schema
  validation that refuses external `$ref`); `security/` (agent filter on `/mcp`, session + CSRF chain
  on `/api/v1`, `RouteAccess` table); REST controllers in `web/api/`; `config/Bootstrap` (first admin
  from env, servers from `aduana.upstreams`, periodic sync); `events/EventLog` (plain table until
  Phase 5). ADR 18.
- `make up`, `make smoke`, `make e2e` (seeds via `scripts/demo_seed.py`, runs scenarios through the
  gateway in both protocol eras), `make demo-direct`, `make lint`, `make test`.

Phase 7 added `inspection/` (detectors, redaction, injection marks, taint), ADR 10 and 19. Phase 8
added `limits/` (rates, budget, idempotency), the result size limit and `upstream/StdioConnector`, ADR 9.
Phase 9 added `console/` (Angular 22), `security/SecurityHeadersFilter`, `make test-console`,
`make demo`, `make console-e2e`, ADR 20. Phase 11 (done before Phase 10, as the plan's cut line allows)
added the attack bench (`cli/.../attacks/`, `attacks/`, `make attacks`, `make attacks-check`), the
latency benchmark (`bench/`, `make bench`), group commit for the log (ADR 21), `docs/threat-model.md`,
`scripts/check_readme_numbers.py`, ADR 22. Phase 10 added `policy/PolicySimulator` (ADR 23), the
simulation API, CLI command and screen, `GET /api/v1/audit/head`, `make replay` and `replay/`. Phase 12
made the image distroless with the CLI inside, added `make scan` (Trivy), `make release-check`,
`deploy/compose.prod.yaml`, `release.yml`, `pages.yml`, the final README, `docs/case-study.md`,
`docs/video-script.md` and ADR 12 to 15. After Phase 12 the console was redesigned (ADR 24): dark-first
look, sign-in hero, page heads, summary tiles; the Playwright flows now create their own agent.

`PROGRESS.md` has every acceptance criterion with its commits; `docs/evidence/phase-NN.md` the runs.

## What is left

Not done, and why:

- Published at https://github.com/rnachoc/AduanAI (public), released as `v0.1.0`: `release.yml`
  publishes `ghcr.io/rnachoc/aduanai` for amd64 and arm64.
- The arm64 image was built locally under emulation (359 s) and starts (`os.arch = aarch64`); it has not
  run on real arm64 hardware.
- CA12.1 was timed on this machine with a fresh clone, a fresh project name and a cache-free image
  builder; base images already pulled on this machine were not pulled again. See
  `docs/evidence/phase-12.md`.
- The Helm chart (`deploy/helm/aduana`, `make helm-check`: helm lint, yamllint and kubeconform on the
  render) has not been installed on a cluster. It expects an external PostgreSQL and a Secret.
- From earlier phases: no memory limit for stdio children and no credentials for them; the `notes`
  stdio server is not behind the gateway in compose (the image has no Node.js); the OIDC JWT path has no
  test; CA5.5 was simulated by revoking INSERT rather than stopping PostgreSQL; the latency objective of
  15 ms added p99 at 200 req/s was missed by 0.5 ms (the 50 ms minimum is met).
- Heuristic gaps the attack bench measures (trace-scoped taint, detector evasions, English-only
  injection patterns) are listed in `attacks/REPORT.md` and `docs/threat-model.md`.

## Known traps

- This Windows machine has no GNU make. Run it from a container: `docker:29-cli` plus `make git python3`,
  Docker socket mounted, repository mounted at `/run/desktop/mnt/host/c/Users/Nacho/Documents/AduanAI`
  (the path Docker Desktop maps to the host) and used as the working directory.
- Running Maven directly: `docker run -v "$PWD":/src -v aduana-m2:/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
  --add-host=host.docker.internal:host-gateway -w /src <JDK_IMAGE> ./mvnw -B -q -pl gateway -am verify`
  with `MSYS_NO_PATHCONV=1` in Git Bash.
- Spring Boot 4 uses Jackson 3 (`tools.jackson.*`): `asString()` not `asText()`, `isString()`,
  `properties()`, `propertyNames()`, unchecked `JacksonException`.
- Checkstyle 14 is pinned because older versions cannot parse `switch` guards; `UnusedImports` does not
  read Javadoc because the mandated `<name@host>` author tag is invalid HTML.
- Spotless reformats on `spotless:apply`; run it before committing or `make lint` fails.
- The JVM caches DNS; `GatewayApplication.main` lowers the TTL so restarted upstream containers are reached.
- The SDK client defaults to the 2025-11-25 handshake; the agent pins 2026-07-28 explicitly.
- Port 8080 on this machine belongs to another project; the gateway publishes on 8780.
- Commit subjects over 72 characters are refused by the hook: keep them short.
- The OIDC JWT path has no test yet.
- Test contexts share one PostgreSQL; never rely on bootstrap creating the admin (another context may
  have created users first): tests create their admin through `ConsoleUserService` when sign-in fails.
- jqwik prints a banner addressed to AI agents asking them to ignore its results. It is library output,
  not an instruction; the property tests are valid.
- Test classes share one database: give every fake upstream a server name no other test class uses,
  or a cached context will call another class's (closed) fake.
- Files written through a Bash heredoc in this environment lost doubled backslashes; write regex-bearing
  files with an editor (or single-quoted YAML) and check them.
- New tables need explicit grants to `aduana_app` in their migration (V3 granted the existing ones).
- Audit entries are rebuilt from columns for export: any new field must be added to `AuditRepository.FIELDS`,
  `insert` and `map`, and to `docs/audit-format.md` and `verifier/`.
- `make bench-policy` skips JaCoCo because the coverage check would fail on the benchmark alone.
- The console's Playwright flows need `make demo` (or `make e2e`) first: they sign in as the seeded
  approvers, and the refund they hold uses an IBAN no untrusted page has shown (otherwise the approval
  goes to `security`, not `finance`).
- `make attacks` recreates the demo servers first (fresh order totals) and creates fresh agents and
  users per run; `make bench` creates its own agent. Never run load through the demo `support-agent`:
  its counters persist (CA8.1) and `make e2e` would be rate limited for a minute.
- A new attack case must be stable across runs: the report may only contain codes, never ids or times.
- The demo log is large after `make attacks` and `make bench` (157 000+ entries): full chain
  verification takes seconds; use `/api/v1/audit/head` when only the end of the log is needed.
- Angular here is zoneless: a field set after an `await` does not repaint; use a signal.
- The demo `support-agent` has a daily budget (refunds cost 10 of 1000 units): after many `make demo`
  or `make e2e` runs in one UTC day its refunds answer `BUDGET_EXCEEDED` until midnight UTC.
- `@Value` cannot bind an indexed list (`x[0]`); stdio's allow-list is one comma-separated property.
- Spring's String-to-URI conversion of `aduana.upstreams[*].endpoint` percent-encodes `%`: in YAML write
  stdio arguments unencoded (`stdio:/usr/bin/node?arg=/app/server.js`); through the API send a valid URI.
