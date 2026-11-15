# ADR 0009: Rate limits, budgets and idempotency keys in PostgreSQL, not Redis

- Status: accepted
- Date: 2026-11-14

## Context

Plan §5.8 asks for call rates per principal and tool and per principal, a daily budget, and idempotency
keys whose second use returns the stored result. CA8.1 asks that the counters survive a restart. Aduana
already depends on PostgreSQL and on nothing else at run time; Redis would be a second stateful service
to run, back up and secure.

## Decision

- `rate_bucket (tenant_id, scope, window_start, count)` holds fixed one-minute windows per scope
  (`tool:<principal>:<tool>`, `principal:<principal>`) and one UTC day for `budget:<principal>`. A call
  increments its windows with `insert … on conflict do update set count = count + n returning count`,
  which is atomic under concurrency.
- The rate is a sliding window approximated from two fixed windows: the current count plus the previous
  window's count weighted by how much of it still overlaps the last minute. It never lets more calls through
  than a true sliding window would count, and can refuse a few early just after a burst.
- A refused call still counts, so retrying in a loop does not get through sooner. The refusal carries
  `retry_after_ms` (the end of the current window, or of the UTC day for the budget).
- `idempotency_record (tenant_id, principal_id, key, args_hash, result)`: the first call with a key inserts
  the row; the primary key lets exactly one concurrent caller win. Retries with the same arguments wait (up
  to `aduana.idempotency.wait`, 10 s) for the result and get it back; other arguments get
  `IDEMPOTENCY_CONFLICT`. Rows expire after `aduana.idempotency.retention` (24 h).
- The 2026-07-28 specification defines no idempotency field, so the key is `_meta["aduana/idempotency-key"]`
  (1 to 200 characters), scoped to the principal.
- A result is kept for every call that may have reached the upstream (including timeouts and tool errors),
  so a retry never runs it twice; a call stopped before forwarding (rate limit, pending approval, refused
  connection) frees its key.
- If the counters or the key store cannot be read or written, the call is refused (`AUDIT_UNAVAILABLE`),
  as with the log (ADR 8).
- Old windows are deleted hourly after two days; expired keys hourly.
- Amended 2026-11-15 (Phase 11, ADR 21): a call's counting runs in one transaction with
  `synchronous_commit = off`. A crash can lose the last fraction of a second of counts, so a few calls
  more than the limit could pass right after a crash; log entries keep synchronous commits.

## Consequences

One node, or several sharing a database, count the same. Every limited call costs two to four small
writes; the throughput is measured in `bench/REPORT.md`. Redis would be faster per call; it can replace
these two tables behind the same `LimitService` and `IdempotencyService` if a measurement ever shows the
need.

## Alternatives considered

- In-memory counters: lost on restart, which CA8.1 forbids, and wrong with two replicas.
- Redis: an extra service for a single-node default deployment.
- A token bucket per scope with a stored refill time: needs a read-modify-write per call instead of one
  upsert.
