# Phase 2 evidence

Run on 2026-11-14.

```
$ make test-gateway
Tests run: 20, Failures: 0, Errors: 0     (ProxyEndpointTest 15, ToolNameTest 3, HealthEndpointTest 2)

$ make up && make e2e
scenario support-happy-path        7 of 7 pass   (agent pinned to 2026-07-28, through the gateway)
scenario injected-refund-direct    2 of 2 pass
scenario support-happy-path        7 of 7 pass   (agent with --legacy: SDK 2025-11-25 initialize handshake)
scenario injected-refund-direct    2 of 2 pass
all steps passed
```

The injected refund still succeeds through the gateway: Phase 2 forwards without deciding anything.
