# Handoff

Rewritten at the end of every session. Last update: 2026-11-14, end of Phase 7.

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

## What is left

Phases 8 to 12 of `docs/PLAN.md` §10. Phase 7 added `inspection/` (Detectors, Redactor, InjectionScanner,
TraceableValues, TaintService, ResultInspector), `V5__inspection.sql`, `docs/detectors.md`, ADR 10 and 19.

Next is Phase 8, limits, idempotency and stdio (plan §5.8):

- `V6__limits.sql`: `rate_bucket (tenant_id, scope, window_start, count)` and `idempotency_record`; grants.
- Rate limits per `(principal, tool)` and per principal (sliding window approximated by fixed windows in
  PostgreSQL, ADR 9), `RATE_LIMITED` with a `retry_after_ms` field; counters survive a restart.
- Daily budget from the catalog's `cost` per tool → `BUDGET_EXCEEDED`.
- Idempotency key in `_meta["aduana/idempotency-key"]`: same key and same arguments → stored result,
  `IDEMPOTENT_REPLAY` entry, no forwarding; same key and other arguments → `IDEMPOTENCY_CONFLICT`.
- Result size limit (`aduana.limits.max-result-bytes`).
- stdio connector: allow-listed commands from configuration, clean environment, restart with backoff,
  newline-delimited JSON-RPC over stdin/stdout; a fourth demo server run over stdio
  (`serveStdio` exists in `@modelcontextprotocol/server`). `upstream_server.transport` already allows `stdio`.

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
