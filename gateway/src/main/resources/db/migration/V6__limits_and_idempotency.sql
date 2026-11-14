-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Ignacio Rodríguez Caro
-- Rate and budget counters, and stored results for idempotent retries (plan §5.8, ADR 9).

create table rate_bucket (
  tenant_id uuid not null references tenant (id),
  scope text not null,
  window_start timestamptz not null,
  count bigint not null default 0,
  primary key (tenant_id, scope, window_start)
);

create index rate_bucket_expiry on rate_bucket (tenant_id, window_start);

create table idempotency_record (
  tenant_id uuid not null references tenant (id),
  principal_id uuid not null,
  key text not null check (length(key) between 1 and 200),
  args_hash text not null,
  result jsonb,
  created_at timestamptz not null,
  expires_at timestamptz not null,
  primary key (tenant_id, principal_id, key),
  foreign key (tenant_id, principal_id) references principal (tenant_id, id)
);

create index idempotency_record_expiry on idempotency_record (tenant_id, expires_at);

grant select, insert, update, delete on rate_bucket, idempotency_record to aduana_app;
