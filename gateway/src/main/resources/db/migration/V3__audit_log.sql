-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Ignacio Rodríguez Caro
-- The hash-chained audit log (plan §5.5), the role the application runs as, and the guard that keeps
-- the log append-only even for the owner (principle 4, ADR 11).

create table audit_entry (
  tenant_id uuid not null references tenant (id),
  seq bigint not null check (seq > 0),
  ts text not null,
  type text not null check (type in (
    'AUTH_FAILED', 'TOOL_NOT_FOUND', 'ARGS_INVALID', 'CALL_DECIDED', 'CALL_COMPLETED', 'CALL_FAILED',
    'APPROVAL_CREATED', 'APPROVAL_DECIDED', 'APPROVAL_EXPIRED', 'RATE_LIMITED', 'IDEMPOTENT_REPLAY',
    'TOOL_DEFINITION_CHANGED', 'POLICY_LOADED', 'POLICY_REJECTED', 'SERVER_CHANGED', 'TOOL_CHANGED',
    'KEY_CREATED', 'KEY_REVOKED', 'CHECKPOINT')),
  principal_id uuid,
  server text,
  tool text,
  args_hash text,
  args_redacted jsonb,
  decision text,
  rule_ids text[] not null default '{}',
  reason_code text,
  policy_bundle_hash text not null,
  approval_id uuid,
  actor_id uuid,
  result_hash text,
  flags text[] not null default '{}',
  latency_ms bigint,
  prev_hash text not null check (prev_hash ~ '^[0-9a-f]{64}$'),
  hash text not null check (hash ~ '^[0-9a-f]{64}$'),
  primary key (tenant_id, seq)
);

create index audit_entry_by_principal on audit_entry (tenant_id, principal_id, seq);
create index audit_entry_by_tool on audit_entry (tenant_id, tool, seq);

create table audit_checkpoint (
  tenant_id uuid not null references tenant (id),
  seq bigint not null,
  hash text not null,
  ts text not null,
  signature text not null,
  key_id text not null,
  primary key (tenant_id, seq),
  foreign key (tenant_id, seq) references audit_entry (tenant_id, seq)
);

-- The checkpoint signing key, sealed with the master key like upstream credentials.
create table audit_signing_key (
  tenant_id uuid not null references tenant (id),
  key_id text not null,
  public_key bytea not null,
  private_key_ciphertext bytea not null,
  private_key_nonce bytea not null,
  created_at timestamptz not null,
  primary key (tenant_id, key_id)
);

create function audit_refuse_change() returns trigger language plpgsql as $$
begin
  raise exception 'the audit log is append-only: % on % refused', tg_op, tg_table_name;
end;
$$;

create trigger audit_entry_append_only before update or delete on audit_entry
  for each row execute function audit_refuse_change();
create trigger audit_entry_no_truncate before truncate on audit_entry
  for each statement execute function audit_refuse_change();
create trigger audit_checkpoint_append_only before update or delete on audit_checkpoint
  for each row execute function audit_refuse_change();
create trigger audit_checkpoint_no_truncate before truncate on audit_checkpoint
  for each statement execute function audit_refuse_change();

-- The role the gateway switches to on every connection. It cannot log in by itself.
do $$
begin
  if not exists (select from pg_roles where rolname = 'aduana_app') then
    create role aduana_app nologin;
  end if;
end;
$$;

grant aduana_app to current_user;
grant usage on schema public to aduana_app;
-- The plain event table of Phase 3 is replaced by the chained log.
drop table event_log;

grant select, insert, update, delete on
  tenant, principal, api_key, console_user, upstream_server, tool, policy_bundle
  to aduana_app;
grant select, insert on audit_entry, audit_checkpoint, audit_signing_key to aduana_app;
revoke update, delete, truncate on audit_entry, audit_checkpoint, audit_signing_key from aduana_app;
grant usage, select on all sequences in schema public to aduana_app;
