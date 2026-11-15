# ADR 0021: Group commit for log entries

- Status: accepted
- Date: 2026-11-15

## Context

The first latency benchmark (Phase 11) measured an added p99 of 262.6 ms at 200 requests per second and
a saturated gateway at 500. Plan CA11.3 asks for at most 50 ms at 200. Each call wrote two log entries,
each in its own transaction holding the tenant's advisory lock until its commit was flushed to disk,
so every entry of every call waited for every earlier flush. The activity stream was also sent from
inside that lock, and the rate counters added three more flushed commits per call.

## Decision

- `AuditLog.append` hands the entry to one writer thread and waits for it. The writer takes everything
  queued (up to 256 entries), and per tenant takes the lock once, appends the entries in arrival order
  (signing checkpoints when due) and commits once. Each caller is answered only after the commit that
  holds its entry, and gets an error if that commit failed: the log still fails closed (ADR 8) and an
  entry is still durable before the call it describes goes ahead.
- `appendWithin` (the approval consumption, ADR 7) still appends inside the caller's transaction under
  the same advisory lock, so the two paths never interleave a chain.
- The activity stream listens after commit, outside the lock.
- Rate and budget counters run in one transaction per call with `synchronous_commit = off` (ADR 9).

## Consequences

Measured on the same machine afterwards (`bench/REPORT.md`): added p99 15.5 ms at 200 requests per
second and 48.7 ms at 500, with no errors. A caller waits at most 30 seconds for the writer before the
call is refused as `AUDIT_UNAVAILABLE`. One writer thread per gateway is a ceiling; the benchmark did
not reach it.

## Alternatives considered

- `synchronous_commit = off` for log entries: an acknowledged entry could be lost in a crash, which
  breaks "logged before forwarded".
- A sequence instead of the lock: the hash chain needs the previous entry's hash, so appends must be
  ordered anyway.
- More database connections: the lock serialises appends whatever the pool size.
