# Phase 5 evidence

Run on 2026-11-14.

```
$ make test-audit        44 tests (JcsTest 30, ChainVerifierTest 12 + property, architecture), 95.91 % lines
$ make mutation          audit: Generated 100 mutations Killed 91 (91 %)
$ make test-gateway      55 tests, 0 failures (AuditLogTest 9 new)
$ make test-verifier     Ran 12 tests … OK
$ make up && make e2e && make audit-verify
wrote 84 entries to out/audit/entries.jsonl
84 entries, last seq 84, 2 checkpoints (signatures checked): intact
verify_chain: 84 entries, 2 checkpoints, intact
$ python verifier/verify_signatures.py verifier/testdata/checkpoints.jsonl verifier/testdata/key.json
verify_signatures: 0 bad signatures
```
