# ADR 0023: Policy simulation over a redacted log

- Status: accepted
- Date: 2026-11-15

## Context

Plan Phase 10 asks to replay logged decisions against a candidate policy. The log keeps arguments
redacted (ADR 19), so a condition such as `destination_iban matches '^XX'` cannot be evaluated on
`[REDACTED:iban:XX20…6001]`. CA10.2 asks that such conditions count as unknown, reported apart, never
as unchanged.

## Decision

- `POST /api/v1/policy/simulate` (admin) and `aduana policy simulate <dir> --url …` replay the
  `CALL_DECIDED` entries of a range (the newest 20 000 entries by default) without loading the
  candidate. The answer names the range, counts changed, unchanged and unknown decisions, groups the
  changes by the candidate's matching rules with up to three example entries, and lists the unknown ones
  with the redacted paths they depend on.
- A call context is rebuilt from the entry: the principal's and the tool's facts as they are now, the
  logged `FLOW_FROM_UNTRUSTED` and `CONTEXT_TAINTED` flags, and the logged (redacted) arguments.
  Detectors that fired are recovered from the redaction placeholders, so `deny_args_containing` applies
  as it did live.
- A rule that could apply and has a condition on a redacted value is uncertain. The candidate is
  evaluated for every combination of uncertain rules (each either matching without those conditions or
  not matching); the decision is known only when every combination gives the same effect. With more than
  ten uncertain rules the decision is unknown outright.

## Consequences

Measured on the demo stack (`docs/evidence/phase-10.md`): the active bundle over its own traffic gives no
change; a candidate that denies every refund changes 27 decisions and leaves none unknown, although every
refund's IBAN is redacted, because denying does not depend on it.

Facts are read as they are now: a principal whose attributes changed, or a tool whose metadata changed,
since a decision is simulated with today's facts. A principal or tool that no longer exists makes the
decision unknown.

## Alternatives considered

Storing arguments in clear for simulation: a second store of personal data (ADR 19). Counting every
decision that touches a redacted value as unknown: true but useless for candidates that do not depend on
those values.
