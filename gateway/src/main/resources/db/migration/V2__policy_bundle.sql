-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Ignacio Rodríguez Caro
-- Policy bundles as loaded, with at most one active bundle per tenant (plan §5.9).

create table policy_bundle (
  tenant_id uuid not null references tenant (id),
  id uuid not null,
  sha256 text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  content jsonb not null,
  loaded_at timestamptz not null,
  loaded_by uuid,
  active boolean not null default false,
  primary key (tenant_id, id)
);

create unique index policy_bundle_one_active on policy_bundle (tenant_id) where active;
create index policy_bundle_by_sha on policy_bundle (tenant_id, sha256);
