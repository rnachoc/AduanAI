# Handoff

Rewritten at the end of every session. Last update: 2026-11-14, end of Phase 4.

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

Phases 5 to 12 of `docs/PLAN.md` §10. Phase 4 added: the `policy` module (pure; parser on strings,
evaluator, bounded regex, type check, test runner), `aduana policy validate|test` (cli, executable jar via
the Spring Boot plugin), the demo bundle in `demo/policy`, gateway step 6 (`policy/PolicyService`,
`V2__policy_bundle.sql`, `/api/v1/policy`), gateway scenarios in `demo/scenarios/gateway/`.

Next is Phase 5, the verifiable log:

- `audit` module (pure): RFC 8785 canonicalisation with the RFC's test vectors (ADR 6: own code or
  `io.github.erdtman:java-json-canonicalization` 1.1), entry hash `SHA-256(prev_hash_bytes || JCS(entry
  without hash))`, Ed25519 checkpoints (JDK), verification of edit, delete, reorder, insert, date change and
  truncation with exported checkpoints.
- `V3__audit.sql`: `audit_entry` (PK `(tenant_id, seq)`), `audit_checkpoint`, a role `aduana_app` with only
  INSERT and SELECT on them, and a `BEFORE UPDATE OR DELETE` trigger that raises even for the owner.
  The gateway must connect as `aduana_app` (Flyway as the owner): two datasources or a `SET ROLE`.
- Replace `events/EventLog` writes with chained entries; record `CALL_DECIDED` before forwarding and fail
  closed with `AUDIT_UNAVAILABLE` (principle 5); `CALL_COMPLETED`/`CALL_FAILED` after.
- `aduana audit verify|export`, `verifier/verify_chain.py` (< 200 lines, stdlib only), `docs/audit-format.md`,
  `GET /.well-known/aduana-audit-key`, `GET /api/v1/audit`, `/audit/verify`, `/audit/export`.

Approvals (Phase 6) are not created yet: `REQUIRE_APPROVAL` answers `APPROVAL_PENDING` and forwards nothing.
Obligations (redaction, `deny_args_containing`) are parsed and joined but applied only from Phase 7.

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
- `make bench-policy` skips JaCoCo because the coverage check would fail on the benchmark alone.
