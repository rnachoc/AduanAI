# ADR 0002: The model proposes, the code decides

- Status: accepted
- Date: 2026-11-14

## Context

An agent's tool call is the output of a language model that may have read attacker-controlled text.
Any control that asks a model whether an action is safe can be talked out of its answer by the
same injection that produced the action.

## Decision

Every decision Aduana takes about a tool call (allow, deny, require approval, redact) is computed by
deterministic code from the policy bundle and facts about the call. No decision depends on text a
model generated: there is no "justification" field, and approvers never see free text from the agent.
Aduana does not call any language model (ADR 14).

## Consequences

- Policies are data, evaluated by a pure function that can be tested and replayed (ADR 5).
- Some judgements a model could make (is this e-mail rude?) are out of reach. That is accepted.
- Heuristic signals (injection patterns, literal taint) only flag; they feed policy and never decide alone.

## Alternatives considered

An LLM "guard" model that scores each call: rejected, because it shares the failure mode it is meant to stop
and adds cost, latency and non-reproducible decisions.
