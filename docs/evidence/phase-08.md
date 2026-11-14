# Phase 8 evidence

Run on 2026-11-14.

```
$ make test-gateway      90 tests, 0 failures (LimitsTest 8, StdioTest 5 new); gateway line coverage 90.93 %
$ make test-demo         23 tests, 0 failures (stdio.test.ts 3 new)
$ make lint              passed
$ make up && make e2e    4 rounds: all steps passed
```

Rate limit on the running stack, default 60 calls per minute per tool, 65 calls to `crm__get_customer`
with one key (the e2e rounds had made two calls the minute before, which the sliding window counts):

```
18:09:13  {"ok": 58, "RATE_LIMITED": 7, "retry": 44323}        retry_after_ms of the first refusal
rate_bucket   tool:<id>:crm__get_customer | 18:09:00 | 65
$ docker compose restart gateway
18:09:26  rate_bucket   tool:<id>:crm__get_customer | 18:09:00 | 65      unchanged
          next call: {"RATE_LIMITED": 1, "retry": 32515}
```

Idempotency on the running stack, three calls with `_meta["aduana/idempotency-key"] = "evidence-key-2"`:

```
{"ok": 3}
audit (newest first): IDEMPOTENT_REPLAY, IDEMPOTENT_REPLAY, CALL_COMPLETED, CALL_DECIDED
```

```
$ make audit-verify
verify_chain: 289 entries, 16 checkpoints, intact
check_log_secrets: 289 entries, 0 values in clear
```

The stdio connector is exercised by `StdioTest` against a JDK-only fake server; the `notes` demo server
is exercised over stdio by `demo/test/stdio.test.ts`. The `notes` server is not behind the gateway in
`compose.yaml`: the gateway image has no Node.js runtime. No memory limit is applied to stdio children.
