# ADR 0014: Aduana never calls a language model

- Status: accepted
- Date: 2026-11-15

## Context

A gateway for AI agents could use a model to classify calls, spot injections or summarise arguments
for approvers. ADR 2 says the model proposes and the code decides; a model inside the gate would be
deciding again.

## Decision

- No component of Aduana calls a language model: not the gateway, the CLI, the console, the verifier,
  nor the demo's scripted agent, whose steps are written in YAML.
- Every decision is made by code a person can read and test: policy rules, detectors with validity
  checks, injection patterns, taint by literal values. Heuristics are reported as heuristics, with their
  measured rate (`attacks/REPORT.md`, `docs/detectors.md`).

## Consequences

Decisions are reproducible: the same call under the same policy gets the same answer, which is what
makes simulation (ADR 23) and the attack bench possible. Paraphrased injections and re-encoded data
escape the heuristics; the deterministic rules are what stop the action.

## Alternatives considered

A model as a second opinion that can only add friction (approval, never allow): still a decision no
one can test or reproduce, and an extra data flow to a third party.
