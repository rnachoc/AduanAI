# Phase 11 evidence

Run on 2026-11-15.

```
$ make attacks           91 cases, 85 stopped; deterministic not stopped: none
$ make attacks (again)   attacks/REPORT.md identical to the previous run
$ make bench             see bench/REPORT.md
$ make e2e               4 rounds: all steps passed (after the benchmark, with its own agent)
$ make test-gateway      verify passed (AuditLogTest +1)
$ cli verify             passed (AttackBenchTest 5)
$ make lint              passed (check_readme_numbers: 0 claims, 0 unbacked)
```

Attack bench ([`attacks/REPORT.md`](../../attacks/REPORT.md)): 91 cases in the ten categories, every
minimum met. Deterministic defences: 78 of 78 cases stopped. Heuristic defences: 7 of 13 cases stopped;
the six that escape are listed in the report (trace-scoped taint, four detector evasions, a non-English
poisoned description).

Latency ([`bench/REPORT.md`](../../bench/REPORT.md)), AMD Ryzen 5 5500, 12 logical CPUs, everything on
one Docker host:

| Rate (req/s) | Added p50 (ms) | Added p95 (ms) | Added p99 (ms) |
|---|---|---|---|
| 50 | 9.6 | 11.5 | 13.8 |
| 200 | 8.4 | 12.4 | 15.5 |
| 500 | 16.4 | 32.1 | 48.7 |

The first measurement, before ADR 21, was 262.6 ms added p99 at 200 req/s and 13842 errors out of 15000
at 500: each log entry was its own flushed commit under the tenant lock. Group commit brought it to the
numbers above. The plan's minimum (at most 50 ms added p99 at 200 req/s) is met; the 15 ms objective is
missed by 0.5 ms in this run.

Defects found and fixed by the bench while writing it: none in the gateway's defences. Corrections to
the cases themselves: authentication failures answer HTTP 401 with a JSON-RPC error, not an `HTTP_401`
code; a newly registered server's tools are pinned at its first sync (plan §5.3) and stopped by the
default deny rather than by review; the truncation case cuts past a checkpoint, because entries after
the newest checkpoint can be cut undetected (stated in the threat model).
