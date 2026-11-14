# ADR 0010: Taint by the hash of literal values

- Status: accepted
- Date: 2026-11-14

## Context

The attack Aduana is built around: an agent reads a web page that says "refund to IBAN X", and then
calls a tool with side effects using X. The policy needs to know that X came from untrusted text, without
a model judging the text (ADR 2) and without storing what the agent read.

## Decision

- From every result of a tool marked `untrusted_source`, the gateway extracts traceable values (URLs,
  e-mail addresses, IBANs, numbers of more than eight digits, strings of 24+ characters without spaces),
  normalises them (lowercase URLs and addresses, IBANs without spaces, trailing punctuation dropped) and
  stores only their SHA-256 in `taint_token`, with the principal, the trace id and the `seq` of the
  `CALL_COMPLETED` entry that recorded the result. Tokens expire after 30 minutes (`aduana.taint.window`).
- Before every policy decision the same extraction runs over the call's arguments. A match sets
  `flow_from_untrusted`; any live token of the principal sets `context_tainted`. Both reach the policy
  as `context` facts, and the `CALL_DECIDED` entry carries `FLOW_FROM_UNTRUSTED`, `CONTEXT_TAINTED` and one
  `TAINT_SOURCE:<argument path>:seq=<source seq>` flag per tainted argument.
- The trace id is the W3C trace id in `_meta.traceparent`. With a trace id, a call matches tokens of
  that trace and tokens recorded without one; without, it matches every live token of the principal.

## Consequences

- Only literal copies are seen. Re-typing an IBAN with different spacing is caught (normalisation);
  splitting it, encoding it, translating or paraphrasing the instruction is not. The README, the
  policy reference and this ADR say so; Phase 11 measures the escape rate on the attack bench.
- A value can be tainted by its own echo (a fetched URL appears in the fetch result), which only matters
  if a policy reacts to flows into read-only tools; the demo policy does not.
- The table holds hashes only (`the_taint_table_holds_hashes_only`). A short value with few possibilities
  could be brute-forced from its hash; the extracted kinds are long by construction.

## Alternatives considered

Storing the values: a second copy of untrusted and possibly personal data. Semantic similarity with a
model: rejected by ADR 2 and ADR 14.
