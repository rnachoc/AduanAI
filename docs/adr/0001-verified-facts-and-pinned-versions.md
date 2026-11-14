# ADR 0001: Verified facts and pinned versions

- Status: accepted
- Date: 2026-11-14

## Context

The plan (`docs/PLAN.md` §4.3, §4.4) requires every version and every protocol fact to be checked
against an official source before code depends on it, with the URL and the date of the check.

## Decision

Use the versions and facts below. All sources were read on **2026-11-14**.

### Versions

| Piece | Pinned | Licence | Source |
|---|---|---|---|
| Java | 25 (LTS), Eclipse Temurin | GPL-2.0 with Classpath Exception (runtime, not a library) | https://adoptium.net/ |
| JDK image | `eclipse-temurin:25-jdk@sha256:b8705e93…` | — | https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags/25-jdk |
| JRE image | `eclipse-temurin:25-jre@sha256:a640968a…` | — | https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags/25-jre |
| Spring Boot | 4.1.2 | Apache-2.0 | https://spring.io/projects/spring-boot |
| Maven | 3.10.0 through Maven Wrapper 3.3.4 | Apache-2.0 | https://maven.apache.org/download.cgi · https://maven.apache.org/wrapper/maven-wrapper-plugin/ |
| maven-compiler-plugin / surefire | 3.16.0 / 3.6.1 | Apache-2.0 | https://repo1.maven.org/maven2/org/apache/maven/plugins/ (maven-metadata.xml) |
| PostgreSQL | 18.7, `postgres:18.7@sha256:9f4fd4aa…` | PostgreSQL | https://www.postgresql.org/ · https://hub.docker.com/_/postgres |
| Flyway | 13.12.2 (`flyway-core`, `flyway-database-postgresql`) | Apache-2.0 | https://repo1.maven.org/maven2/org/flywaydb/flyway-core/maven-metadata.xml |
| networknt json-schema-validator | 3.0.9 | Apache-2.0 | https://repo1.maven.org/maven2/com/networknt/json-schema-validator/maven-metadata.xml |
| Testcontainers | 2.0.5 | MIT | https://repo1.maven.org/maven2/org/testcontainers/testcontainers/maven-metadata.xml |
| JUnit | 6.1.3 (`junit-bom`) | EPL-2.0 | https://repo1.maven.org/maven2/org/junit/junit-bom/maven-metadata.xml |
| AssertJ | 3.27.7 | Apache-2.0 | https://repo1.maven.org/maven2/org/assertj/assertj-core/maven-metadata.xml |
| jqwik | 1.10.2 | EPL-2.0 | https://repo1.maven.org/maven2/net/jqwik/jqwik/maven-metadata.xml |
| ArchUnit | 1.5.1 | Apache-2.0 | https://repo1.maven.org/maven2/com/tngtech/archunit/archunit-junit5/maven-metadata.xml |
| PIT / pitest-junit5-plugin | 1.30.2 / 1.2.3 | Apache-2.0 | https://repo1.maven.org/maven2/org/pitest/ |
| JaCoCo | 0.8.16 | EPL-2.0 | https://repo1.maven.org/maven2/org/jacoco/jacoco-maven-plugin/0.8.16/ |
| Spotless / google-java-format | 3.11.0 / 2.0.0 | Apache-2.0 | https://repo1.maven.org/maven2/com/diffplug/spotless/spotless-maven-plugin/maven-metadata.xml |
| Checkstyle plugin | 3.6.0 (Checkstyle itself is LGPL-2.1: build tool, never shipped) | Apache-2.0 | https://repo1.maven.org/maven2/org/apache/maven/plugins/maven-checkstyle-plugin/3.6.0/ |
| picocli | 4.7.7 | Apache-2.0 | https://repo1.maven.org/maven2/info/picocli/picocli/maven-metadata.xml |
| Angular | 22.2.6 | MIT | https://registry.npmjs.org/@angular/core/latest |
| MCP TypeScript SDK | `@modelcontextprotocol/server` and `/client` 2.4.0 | Apache-2.0 | https://registry.npmjs.org/@modelcontextprotocol/server/latest · https://github.com/modelcontextprotocol/typescript-sdk/releases |
| Node image | `node:24-slim@sha256:dc26ae92…` | — | https://hub.docker.com/_/node |
| Python image | `python:3.13-slim@sha256:e48f59cd…` | — | https://hub.docker.com/_/python |
| Playwright | 1.65.1 | Apache-2.0 | https://registry.npmjs.org/playwright/latest |
| axe-core / @axe-core/playwright | 4.14.0 | MPL-2.0 | https://registry.npmjs.org/axe-core/latest |
| gitleaks | v8.30.1, `ghcr.io/gitleaks/gitleaks:v8.30.1@sha256:c00b6bd0…` | MIT | https://api.github.com/repos/gitleaks/gitleaks/releases/latest |
| editorconfig-checker | 4.0.2, `mstruebing/editorconfig-checker:4.0.2@sha256:2ba6232b…` | MIT | image label `org.opencontainers.image.version` |
| ruff | 0.17.5 | MIT | https://pypi.org/pypi/ruff/json |
| yamllint | 1.38.0 (lint tool, never shipped) | GPL-3.0-or-later | https://pypi.org/pypi/yamllint/json |
| k6 | v2.4.0 (external tool in CI only, never distributed) | AGPL-3.0 | https://api.github.com/repos/grafana/k6 |
| Trivy | v0.76.2 | Apache-2.0 | https://api.github.com/repos/aquasecurity/trivy/releases/latest |
| actions/checkout | v7.0.1, pinned to commit `3d3c42e5…` | MIT | https://api.github.com/repos/actions/checkout/releases/latest |

Full digests live in `scripts/images.env`, `Dockerfile` and `compose.yaml`; `scripts/check_pinned_images.py`
fails the lint if one is missing.

Argon2id: Spring Security's `Argon2PasswordEncoder` "requires BouncyCastle"
(https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).
That choice is deferred to Phase 3 and gets its own ADR.

RFC 8785: `io.github.erdtman:java-json-canonicalization` 1.1 (Apache-2.0) exists but dates from 2018.
ADR 6 (Phase 5) decides between it and an in-house implementation.

### Protocol facts (plan §4.4), checked against the specification

Sources: https://modelcontextprotocol.io/specification/2026-07-28 (overview, `/changelog`, `/basic`,
`/basic/versioning`, `/basic/transports/streamable-http`, `/server/discover`, `/server/tools`,
`/basic/patterns/mrtr`, `/basic/authorization`), https://modelcontextprotocol.io/extensions/tasks/overview,
https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle.

| Plan statement | Result |
|---|---|
| Current revision is 2026-07-28, first since 2025-11-25 | Confirmed |
| Core is stateless; `initialize` and `Mcp-Session-Id` are gone | Confirmed. "There is no negotiation handshake." Servers ignore `Mcp-Session-Id` and do not mint it. `Last-Event-ID` resumption and the GET stream are gone too; GET and DELETE on the endpoint SHOULD return 405 |
| Version and capabilities travel in `_meta` on every request | Confirmed: `io.modelcontextprotocol/protocolVersion` and `io.modelcontextprotocol/clientCapabilities` are REQUIRED, `io.modelcontextprotocol/clientInfo` SHOULD be sent. Missing required fields: -32602 and HTTP 400 |
| Servers must implement `server/discover` | Confirmed. Result: `resultType`, `supportedVersions`, `capabilities`, `_meta["io.modelcontextprotocol/serverInfo"]`, optional `instructions` |
| HTTP+SSE is deprecated; transport is Streamable HTTP | Confirmed. Every POST carries `MCP-Protocol-Version` (must equal the `_meta` value, else -32020 HeaderMismatch, HTTP 400), `Mcp-Method`, and `Mcp-Name` for `tools/call`; `Accept` lists `application/json` and `text/event-stream`; a response is one JSON object or an SSE stream |
| Authorization changes SEP-2352 and SEP-2468 (`iss`) | Confirmed. Also: an MCP server "MUST NOT accept or transit any other tokens", which matches principle 3 |
| Tasks and MRTR appear | **Corrected**: MRTR is core (`resultType: "input_required"`); Tasks moved to an official extension `io.modelcontextprotocol/tasks`, used only when the client declares it |
| Roots, Sampling and Logging are being retired | Confirmed as deprecated. `ping`, `logging/setLevel` and `notifications/roots/list_changed` are removed outright |
| (not in the plan) `tools/list` results | `ttlMs` and `cacheScope` are required; tools come in deterministic order |
| (not in the plan) tool errors | Unknown tool is a JSON-RPC error -32602; execution failures are results with `isError: true` |
| (plan §5.6) trace id in `_meta` | Standard keys `traceparent`, `tracestate`, `baggage` (W3C). Aduana takes the trace id from `traceparent` |
| (plan §5.8) idempotency key in `_meta` | The specification defines none, so Aduana uses `aduana/idempotency-key` |

## Consequences

Builds are reproducible from the pinned images. Bumping a version means editing this ADR with a new
source and date. The protocol corrections feed ADR 4 and ADR 7.

## Alternatives considered

Floating tags (`latest`, `25-jdk`) were rejected: a build would change without a commit.
