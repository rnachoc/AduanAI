-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Ignacio Rodríguez Caro
-- Approvals bound to the exact call (plan §5.4): one row per request for a person's decision.

create table approval (
  tenant_id uuid not null references tenant (id),
  id uuid not null,
  binding_hash text not null check (binding_hash ~ '^[0-9a-f]{64}$'),
  principal_id uuid not null,
  tool_id uuid not null,
  tool text not null,
  args_hash text not null,
  args_redacted jsonb not null,
  policy_bundle_hash text not null,
  rule_ids text[] not null default '{}',
  flags text[] not null default '{}',
  approvers_group text not null,
  state text not null check (state in ('pending', 'approved', 'rejected', 'expired', 'consumed')),
  created_at timestamptz not null,
  expires_at timestamptz not null,
  decided_by uuid,
  decided_at timestamptz,
  consumed_at timestamptz,
  primary key (tenant_id, id),
  foreign key (tenant_id, principal_id) references principal (tenant_id, id)
);

create index approval_by_binding on approval (tenant_id, binding_hash, created_at desc);
create index approval_by_state on approval (tenant_id, state, created_at);

grant select, insert, update on approval to aduana_app;
