-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Ignacio Rodríguez Caro
-- Hashes of values seen in untrusted tool results (plan §5.6), and injection marks on tool descriptions.
-- Values are never stored, only the SHA-256 of their normalised form.

create table taint_token (
  tenant_id uuid not null references tenant (id),
  principal_id uuid not null,
  trace_key text not null default '',
  value_sha256 text not null check (value_sha256 ~ '^[0-9a-f]{64}$'),
  source_seq bigint not null,
  expires_at timestamptz not null,
  foreign key (tenant_id, principal_id) references principal (tenant_id, id)
);

create index taint_token_lookup on taint_token (tenant_id, principal_id, value_sha256, expires_at);
create index taint_token_expiry on taint_token (tenant_id, expires_at);

alter table tool add column description_flags text[] not null default '{}';

grant select, insert, delete on taint_token to aduana_app;
