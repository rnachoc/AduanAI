# ADR 0008: Fail closed when the log is unavailable

- Status: accepted
- Date: 2026-11-14

## Context

Principle 5: what is not recorded is not executed. A gateway that forwards calls while its log is down
gives an attacker a window: break the log, then act.

## Decision

- The `CALL_DECIDED` entry is appended and committed in its own transaction (`REQUIRES_NEW`) before the
  call is forwarded. If the append throws, the agent gets `AUDIT_UNAVAILABLE` and nothing is forwarded
  (`when_the_log_cannot_be_written_nothing_reaches_the_upstream`).
- Administration events are appended inside the change: if the append fails, the change rolls back.
- If `CALL_COMPLETED` cannot be written after the upstream answered, the result is still returned (the
  action already happened) and the failure is logged for the operator; the `CALL_DECIDED` entry proves the
  call was authorised.
- Appends for a tenant are serialised with a PostgreSQL transaction advisory lock, so `seq` never repeats
  and the chain never forks (`fifty_threads_appending_leave_no_gap_and_no_fork`).

## Consequences

Every call costs one extra committed write before it is forwarded; the latency cost is measured in Phase 11.
A database outage stops all tool calls, which is the intended trade-off.

## Alternatives considered

Buffering entries in memory and forwarding anyway: loses entries on a crash, exactly when they matter.
