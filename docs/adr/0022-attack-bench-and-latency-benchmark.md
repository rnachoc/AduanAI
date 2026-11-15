# ADR 0022: How the attack bench and the latency benchmark run

- Status: accepted
- Date: 2026-11-15

## Context

Plan Phase 11 asks for `aduana attacks run` over at least 80 cases with a generated report that CI
keeps current, and a latency report at 50, 200 and 500 requests per second.

## Decision

- Cases are YAML (`attacks/cases/`), one file per category. Steps call tools as a fresh agent per case
  (`call`, `raw`, `parallel`, `repeat`), use the administration API as seeded console users (`http`,
  `approve`, `reject`), run SQL as the gateway's application role (`sql`), tamper with an exported log
  and verify it (`tamper`), read the case's own log entries (`log`), and register a poisoned MCP server
  that the runner itself serves (`stub`). Every step with `expect` must hold for the case to count as
  stopped.
- A case is `deterministic` when a rule always stops it, and heuristic when it depends on taint by
  literal value or on pattern matching. The run fails when a deterministic case is not stopped;
  heuristic results are reported as measured, with the ones that escape listed.
- The report contains nothing that changes between runs (no ids, times or counts that depend on the
  database), so `make attacks-check` can fail CI when the committed report differs.
- The CLI depends on the PostgreSQL JDBC driver (BSD-2-Clause, the version Spring Boot manages, already
  used by the gateway) for the `sql` steps.
- The latency benchmark is a single-file Java program (`bench/Bench.java`, JDK only) run with
  `java bench/Bench.java`: open-loop constant rate, latency counted from each request's due time, a
  dedicated agent per run so its rate counters never touch the demo agents.

## Consequences

`make attacks` and `make bench` need the stack up. The benchmark shares the machine with the gateway,
PostgreSQL and the demo server, so its numbers describe that setup, not a tuned deployment.

## Alternatives considered

A Python runner: the CLI already has the verifier and the HTTP plumbing in Java. A load tool such as
k6 or wrk: another dependency and another image, for one request shape.
