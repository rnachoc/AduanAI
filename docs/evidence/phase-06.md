# Phase 6 evidence

Run on 2026-11-14.

```
$ make test-gateway      63 tests, 0 failures (ApprovalTest 8 new)
$ make up && make e2e
all steps passed                                   (support and injected-refund scenarios)
demo_approve: approved 1 approvals of payments__refund
all steps passed                                   (refund-after-approval: executes once, then pending again)
… and the same round with the 2025-11-25 handshake.
```
