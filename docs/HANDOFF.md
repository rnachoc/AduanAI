# Handoff

Rewritten at the end of every session. Last update: 2026-11-14, end of Phase 6.

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

Phases 7 to 12 of `docs/PLAN.md` §10. Phase 6 added `approval/` (service, repository), `V4__approval.sql`,
`/api/v1/approvals*`, `/api/v1/users`, `/api/v1/events` (SSE fed by `AuditAppended` events),
`scripts/demo_approve.py`, `scripts/e2e_round.sh`.

Next is Phase 7, redaction, injection marks and taint (plan §5.6, §5.7):

- `gateway/.../inspection/`: detectors `email`, `phone_es`, `dni_nie` (control letter), `iban` (mod 97),
  `card` (Luhn), `secret` (patterns + entropy); `docs/detectors.md` with precision and recall measured on a
  labelled set in test resources.
- Apply obligations in `ToolCallService`: `deny_args_containing` before the decision is recorded,
  `flag_args_containing` as flags, `redact_result` on the upstream result; store `args_redacted` redacted
  in `CALL_DECIDED` and approvals (the hash stays over the originals).
- Injection patterns in `gateway/src/main/resources/injection-patterns.yaml`: flag (`INJECTION_PATTERN`) and
  wrap results, never block; also scan tool descriptions at sync.
- Taint: `V5__taint.sql` (`taint_token` with value hashes only), extract URLs, e-mails, IBANs, numbers of
  more than 8 digits and strings of 24+ characters from results of `untrusted_source` tools; in step 5 set
  `flow_from_untrusted` (with the source `seq`) and `context_tainted` in `CallContext.ContextFacts` instead of
  `CLEAN`; `trace_key` from `_meta.traceparent`.
- CA7.2 demo: the agent fetches `blog.example.com/refund-tips` then refunds a small amount to the attacker
  IBAN: approval with `FLOW_FROM_UNTRUSTED` and the source `seq` in the entry.

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
- New tables need explicit grants to `aduana_app` in their migration (V3 granted the existing ones).
- Audit entries are rebuilt from columns for export: any new field must be added to `AuditRepository.FIELDS`,
  `insert` and `map`, and to `docs/audit-format.md` and `verifier/`.
- `args_redacted` still holds the arguments as sent; Phase 7 must redact them before they are written.
- `make bench-policy` skips JaCoCo because the coverage check would fail on the benchmark alone.
