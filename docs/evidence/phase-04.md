# Phase 4 evidence

Run on 2026-11-14 on the owner's machine (12 CPUs, Docker Desktop 29.8.2, Java 25.0.5).

```
$ make test-policy        47 tests, JaCoCo line coverage 96.63 % (minimum 95 %)
$ make mutation           Generated 272 mutations Killed 254 (93 %), threshold 80 %
$ make bench-policy       rules=200 samples=20000 median_us=4.419 p99_us=13.156
$ make policy-test        valid: 13 rules, 45 tests … 45 passed, 0 failed
$ make test-gateway       46 tests, 0 failures (PolicyPipelineTest 7 new)
$ make up && make e2e
scenario support-through-aduana          7 of 7 pass (refund → APPROVAL_PENDING, evil.test → POLICY_DENIED)
scenario injected-refund-through-aduana  3 of 3 pass (refund → POLICY_DENIED, payments never saw it)
… and the same 10 steps again with the 2025-11-25 handshake.
```
