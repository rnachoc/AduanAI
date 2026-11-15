# Phase 10 evidence

Run on 2026-11-15, on the demo stack after `make demo` (the log holds 157 185 entries, most from the
attack bench and the latency benchmark).

```
$ make test-gateway      SimulationTest 5, AuditLogTest +1 (head), RouteAccessTest: passed
$ make test-console      22 tests, 0 failures; lines 95.76 %
$ make test-scripts      test_build_replay.py 4: passed
$ make replay            build_replay: 40 steps written to replay/index.html
$ make console-e2e       11 passed (adds: replay opened from disk, simulation of the active policy)
```

Simulation of the active bundle over its own traffic (CA10.1), newest 20 000 entries:

```
$ aduana policy simulate demo/policy --url http://gateway:8080
seq 137186 to 157185, 9476 decisions: 0 change, 9452 unchanged, 24 unknown
  unknown seq 157023 payments__refund: REDACTED_ARGUMENT $.destination_iban
  …
```

The 24 unknown decisions are refunds whose outcome depends on `destination_iban matches '^XX'`, and the
log keeps the IBAN redacted (CA10.2: reported apart, not counted as unchanged; `SimulationTest`
checks the same on its own traffic).

A candidate that adds a rule denying every refund:

```
seq 137186 to 157185, 9476 decisions: 27 change, 9449 unchanged, 0 unknown
  require_approval -> deny x24 by candidate-no-refunds-at-all
  require_approval -> deny x3 by untrusted-flow-into-side-effects, candidate-no-refunds-at-all
```

Denying does not depend on the redacted IBAN, so no decision is unknown (ADR 23).

Replay page (CA10.3): `replay/index.html` is one file with its data, styles and script inline; the
Playwright flow opens it as `file:///src/replay/index.html`, steps with the button and the arrow key,
and checks that the page made no request other than `file:`.

Fixed while testing: the activity screen called the full chain verification only to learn the newest
seq (6.5 s with 157 138 entries); it now uses `GET /api/v1/audit/head`, and the log screen shows entries
while the verification runs.
