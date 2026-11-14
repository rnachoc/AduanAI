-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Ignacio Rodríguez Caro
-- Identities, keys, console users and the catalog of upstream servers and tools (plan §5.9).
-- Every table carries tenant_id and its indexes start with it, even with a single tenant (principle 10).

create table tenant (
  id uuid primary key,
  tenant_id uuid not null generated always as (id) stored,
  name text not null unique,
  created_at timestamptz not null
);

insert into tenant (id, name, created_at)
values ('00000000-0000-0000-0000-000000000001', 'default', now());

create table principal (
  tenant_id uuid not null references tenant (id),
  id uuid not null,
  kind text not null check (kind in ('agent', 'user')),
  name text not null,
  attributes jsonb not null default '{}'::jsonb,
  groups text[] not null default '{}',
  status text not null default 'active' check (status in ('active', 'disabled')),
  created_at timestamptz not null,
  primary key (tenant_id, id),
  unique (tenant_id, name)
);

create table api_key (
  tenant_id uuid not null,
  id uuid not null,
  principal_id uuid not null,
  prefix text not null,
  key_sha256 bytea not null,
  created_at timestamptz not null,
  expires_at timestamptz,
  revoked_at timestamptz,
  primary key (tenant_id, id),
  foreign key (tenant_id, principal_id) references principal (tenant_id, id)
);

-- The tenant is unknown until the key is found, so this one lookup index cannot start with tenant_id.
create unique index api_key_by_hash on api_key (key_sha256);

create table console_user (
  tenant_id uuid not null,
  id uuid not null,
  principal_id uuid not null,
  email text not null,
  password_hash text not null,
  role text not null check (role in ('admin', 'approver', 'viewer')),
  created_at timestamptz not null,
  primary key (tenant_id, id),
  foreign key (tenant_id, principal_id) references principal (tenant_id, id)
);

-- Log-in happens before the tenant is known, for the same reason as api_key_by_hash.
create unique index console_user_by_email on console_user (lower(email));

create table upstream_server (
  tenant_id uuid not null references tenant (id),
  id uuid not null,
  name text not null,
  transport text not null check (transport in ('http', 'stdio')),
  endpoint text not null,
  credential_ciphertext bytea,
  credential_nonce bytea,
  strict boolean not null default true,
  status text not null default 'active' check (status in ('active', 'disabled')),
  timeout_ms integer not null default 10000 check (timeout_ms > 0),
  created_at timestamptz not null,
  primary key (tenant_id, id),
  unique (tenant_id, name),
  check ((credential_ciphertext is null) = (credential_nonce is null))
);

create table tool (
  tenant_id uuid not null,
  id uuid not null,
  server_id uuid not null,
  name text not null,
  definition jsonb not null,
  definition_sha256 bytea not null,
  pending_definition jsonb,
  pending_sha256 bytea,
  side_effect boolean not null default false,
  untrusted_source boolean not null default false,
  sensitivity text not null default 'low' check (sensitivity in ('low', 'medium', 'high')),
  cost integer not null default 0 check (cost >= 0),
  enabled boolean not null default true,
  state text not null check (state in ('active', 'changed_pending_review', 'new_pending_review', 'removed')),
  updated_at timestamptz not null,
  primary key (tenant_id, id),
  unique (tenant_id, server_id, name),
  foreign key (tenant_id, server_id) references upstream_server (tenant_id, id)
);

-- A plain event table until the hash-chained audit log arrives in Phase 5.
create table event_log (
  tenant_id uuid not null references tenant (id),
  id bigint generated always as identity,
  ts timestamptz not null,
  type text not null,
  principal_id uuid,
  detail jsonb not null default '{}'::jsonb,
  primary key (tenant_id, id)
);
