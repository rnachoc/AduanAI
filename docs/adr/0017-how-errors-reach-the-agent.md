# ADR 0017: How errors reach the agent

- Status: accepted
- Date: 2026-11-14

## Context

Plan §6.3 says every error towards the agent travels "in the MCP error result, structured field
`code` + `message` + `hint`". The 2026-07-28 tools page
(https://modelcontextprotocol.io/specification/2026-07-28/server/tools, read 2026-11-14) separates two
kinds of failure: protocol errors, such as an unknown tool, are JSON-RPC errors (`-32602`); tool
execution errors (API failures, validation, business logic) are results with `isError: true`, so the
model can read and react to them. The plan says the specification wins when they disagree.

## Decision

- `TOOL_NOT_FOUND` is a JSON-RPC error `-32602` with `data: {code, message, hint}`. The message is the
  same for an unknown server, an unknown tool and (from Phase 3) a tool the principal may not see,
  so the response does not reveal which.
- Every other code (`ARGS_INVALID`, `POLICY_DENIED`, `APPROVAL_PENDING`, `UPSTREAM_TIMEOUT`, …) is a
  `tools/call` result with `isError: true`, `structuredContent: {code, message, hint, …}` and the same
  object serialised in a text block, as the specification asks of structured results.
- Messages and hints are fixed per code (`AgentErrorCode`). They never contain rule text, rule ids or
  the names of other tools.
- An upstream's own `isError` result passes through unchanged: it is the tool's answer, not Aduana's.
- Requests in the 2025-11-25 era get JSON-RPC errors with HTTP 200, as that revision expects; modern
  requests get the HTTP status the 2026-07-28 transport page assigns (400, 404, 413).

## Consequences

Agents and the scripted agent read a single field (`code`) whichever kind of error it is. The console
and the audit log use the same codes.

## Alternatives considered

Everything as `isError` results: breaks the specification for unknown tools. Everything as JSON-RPC
errors: hides policy denials from the model, which then retries blindly.
