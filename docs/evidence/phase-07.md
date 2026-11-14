# Phase 7 evidence

Run on 2026-11-14.

```
$ make test-gateway      77 tests, 0 failures (DetectorsTest 6, InspectionTest 8 new)
$ make up && make e2e    4 rounds: all steps passed (includes injected-small-refund.yaml)
$ make audit-verify
72 entries, last seq 72, 3 checkpoints (signatures checked): intact
verify_chain: 72 entries, 3 checkpoints, intact
check_log_secrets: 72 entries, 0 values in clear
$ grep FLOW_FROM_UNTRUSTED out/audit/entries.jsonl
"flags":["REDACTED","CONTEXT_TAINTED","FLOW_FROM_UNTRUSTED","TAINT_SOURCE:$.destination_iban:seq=25",…]
```

The stack's database volume was recreated before this run: entries written before Phase 7 hold arguments
in clear and, being append-only, stay that way.
