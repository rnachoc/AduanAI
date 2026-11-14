# ADR 0004: Own MCP implementation in the gateway; 2025-11-25 compatibility through `initialize`

- Status: accepted
- Date: 2026-11-14

## Context

The plan prefers the official Java SDK only if it supports revision 2026-07-28. On 2026-11-14
(https://github.com/modelcontextprotocol/java-sdk/releases) the latest stable Java SDK is v2.1.0,
which tracks 2025-11-25. Support for 2026-07-28 exists only in milestones (v2.2.0-M1, v2.2.0-M2,
package `io.modelcontextprotocol.modern`), server side only; the release notes list a modern client,
tasks and SEP-2243 custom headers as not yet supported. The plan forbids candidate versions, and
Aduana needs both a server side (towards agents) and a client side (towards upstream servers).

The TypeScript SDK 2.4.0 (`@modelcontextprotocol/server`, `@modelcontextprotocol/client`) is stable
and its release notes cover 2026-07-28.

## Decision

1. The gateway implements the subset of 2026-07-28 it needs (plan §6.1) itself, on Jackson and the JDK
   HTTP client: JSON-RPC 2.0 envelopes, `server/discover`, `tools/list`, `tools/call`, the required
   `_meta` fields and HTTP headers, JSON and SSE responses from upstreams. The protocol code lives in
   one package (`io.github.rnachoc.aduana.gateway.mcp`) so a later revision is a contained change.
2. The demo servers and the scripted agent use the official TypeScript SDK 2.4.0.
3. Compatibility with 2025-11-25: the specification allows a dual-era server where an `initialize`
   request selects legacy semantics. Aduana answers `initialize` minimally (protocol version,
   `tools` capability, server info) and accepts later requests that lack the modern `_meta` fields
   by treating them as 2025-11-25. The tool pipeline is shared; only envelope parsing differs.
   If this turns out to need a second pipeline, it is dropped and documented as unsupported.

## Consequences

- No dependency on a milestone SDK. Conformance is Aduana's job: Phase 2 adds contract tests that
  run the demo servers (official SDK) through the gateway.
- When the Java SDK ships a stable modern client and server, this ADR is revisited.

## Alternatives considered

- Java SDK 2.2.0-M2: milestone, no modern client. Rejected by the plan's version rule.
- Java SDK 2.1.0 (2025-11-25 only): would make the target revision unreachable.
