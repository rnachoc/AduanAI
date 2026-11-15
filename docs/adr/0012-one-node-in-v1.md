# ADR 0012: One node in v1

- Status: accepted
- Date: 2026-11-15

## Context

Plan §2 says v1 is neither multi-region nor highly available: one node, one database. Several parts
depend on that: the policy cache, the approval hold, the stdio children and the log writer live in the
gateway's process.

## Decision

- Aduana v1 runs as one gateway process with one PostgreSQL database.
- State that must survive a restart lives in PostgreSQL: the catalog, policies, the log, approvals,
  taint tokens, rate counters and idempotency keys. In-process state is a cache or a connection: the
  active policy (reloaded from the database), the log writer's queue, stdio child processes.
- The tenant advisory lock and the single log writer (ADR 21) serialise appends; a second gateway
  against the same database would still append a valid chain, because the lock is in the database, but
  this is not tested.

## Consequences

A gateway restart interrupts calls in flight and drops held approval waits (the agent retries and finds
the approval in the database). A database outage stops every call (ADR 8). Scaling out means testing two
gateways against one database first; nothing in the design forbids it.

## Alternatives considered

Clustering from the start (shared cache, leader election for the writer and the scheduled jobs): more
moving parts than v1 needs, and the plan rules it out.
