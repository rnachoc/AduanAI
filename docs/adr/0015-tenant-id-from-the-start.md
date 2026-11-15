# ADR 0015: `tenant_id` from the start

- Status: accepted
- Date: 2026-11-15

## Context

v1 serves one organisation, but adding tenants later means rewriting every table, index, query and the
log's hash chain.

## Decision

- Every table has `tenant_id uuid not null`, and its keys and indexes start with it (plan §5.9).
- Every repository query filters by tenant; services take the tenant from the principal or the console
  user, never from the request body.
- The log has one hash chain and one checkpoint key per tenant; an export and its verification are per
  tenant.
- v1 runs with one tenant, `Tenants.DEFAULT`.

## Consequences

Every query carries one more condition. A second tenant needs a way to create it and to route agents and
users to it, not a schema change.

## Alternatives considered

One database or schema per tenant: stronger isolation, but a migration and a connection pool per
tenant, and nothing in v1 needs it.
