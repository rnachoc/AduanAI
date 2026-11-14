# ADR 0011: Least privilege in the database

- Status: accepted
- Date: 2026-11-14

## Context

Principle 4: the log is append-only. A gateway compromise (or an SQL injection) must not be able to
rewrite history, and neither should a careless owner.

## Decision

- Migration V3 creates the role `aduana_app` (`NOLOGIN`) and grants it `SELECT, INSERT` only on
  `audit_entry`, `audit_checkpoint` and `audit_signing_key`, and full DML on the other tables.
- The gateway has two pools (`DatabaseConfiguration`): Flyway connects as the owner; the application pool
  runs `SET ROLE aduana_app` once per physical connection, so every query the gateway sends runs with the
  role's privileges. The owner credentials are therefore still in the gateway's environment; a fully
  compromised process could `RESET ROLE`. The defences against that are the triggers, the hash chain and
  checkpoints kept outside the database.
- `BEFORE UPDATE OR DELETE` row triggers and `BEFORE TRUNCATE` statement triggers on `audit_entry` and
  `audit_checkpoint` raise an exception, so even the owner's ordinary statements fail
  (`even_the_owner_cannot_update_delete_or_truncate_entries`). A superuser can disable a trigger; the
  verifier then catches the change (`an_owner_who_bypasses_the_trigger_is_caught_by_verification`).
- Later migrations must grant `aduana_app` what it needs on new tables explicitly.

## Consequences

Deployments that want stronger separation give the gateway a login role equal to `aduana_app` and run
migrations from a separate job; the code supports it by pointing both pools at different credentials
(`spring.flyway.user`), which is not wired in v1.

## Alternatives considered

A login role with a password in the migration: a secret in the repository. Row-level security: does not
stop `UPDATE` by a role that owns the table.
