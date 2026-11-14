# Phase 3 evidence

Run on 2026-11-14.

```
$ make test-gateway
HealthEndpointTest 2, IdentityAndCatalogTest 10, ProxyEndpointTest 15, CatalogUnitTest 6,
RouteAccessTest 3, ToolNameTest 3: 39 tests, 0 failures (PostgreSQL 18.7 through Testcontainers)

$ make up && make e2e
all steps passed      (2026-07-28, agent key from scripts/demo_seed.py)
all steps passed      (2025-11-25 handshake, same key)

$ make lint
check_licenses: 83 Maven lines, 1 npm lock files, 0 problems
```
