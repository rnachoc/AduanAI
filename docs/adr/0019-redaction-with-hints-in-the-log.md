# ADR 0019: Redact arguments in the log, with hints for approvers

- Status: accepted
- Date: 2026-11-14

## Context

Plan §5.7 says the log always stores arguments redacted, and §5.4 says an approver sees the arguments
"redacted according to policy". For a refund the decisive argument is the destination IBAN: fully
redacted, the approver cannot tell the customer's account from the attacker's.

## Decision

- Every argument string is redacted with all six detectors before it is written to the log or to an
  approval (CA7.3), whatever the policy says. The replacement keeps a hint: `[REDACTED:iban:XX20…6001]`
  (first and last four characters), `[REDACTED:email:a***@example.com]`, `[REDACTED:card:…1111]`, the last
  three characters for phones and DNI/NIE, and no hint for secrets.
- `args_hash` is computed over the original arguments, so an approval still binds to the exact values.
- Results sent to the agent are redacted only as the matching rules' `redact_result` asks, and without
  hints (`[REDACTED:iban]`): the agent gets nothing it could reuse.
- `scripts/check_log_secrets.py` scans an export for whole e-mail addresses and IBANs, cards and DNIs
  that pass their checks; `make audit-verify` runs it.

## Consequences

An approver distinguishes accounts by their ends, which is what bank interfaces show too. A hint is not a
secret: four characters of an IBAN do not let anyone move money.

## Alternatives considered

Storing arguments in clear for approvers only: a second store of personal data. Showing nothing: approvals
become blind, and blind approvals get rubber-stamped.
