# ADR 0005: Declarative policy without an expression language

- Status: accepted
- Date: 2026-11-14

## Context

Policies decide whether an agent's call goes ahead. Gateways in the comparison (`docs/comparison.md`)
use CEL or OPA/Rego. An expression language is powerful, but a policy that can compute can also hide
mistakes, is hard to review, and cannot be reasoned about mechanically (for example "adding a deny never
relaxes a decision").

## Decision

- Policies are YAML data validated by a JSON Schema: matchers on principal, tool, arguments and data-flow
  marks, a closed list of comparators, a JSONPath subset (`$`, `.field`, `[n]`, `[*]`).
- Every rule is evaluated; `deny` > `require_approval` > `allow`; nothing matched means deny.
- The evaluator is a pure function in the `policy` module, with no Spring, I/O, clock or randomness
  (enforced by `PolicyArchitectureTest`). Parsing works on strings; reading files is the caller's job.
- `matches` uses `java.util.regex` with two bounds instead of a backtracking-free engine, which would be a
  new dependency: patterns with back-references or look-around are refused at load, and each match runs
  over a `CharSequence` that aborts after 200 000 character reads (inputs over 4096 characters never
  match). A catastrophic pattern such as `^(a+)+$` on 40 `a` and a `!` returns in well under a second
  (`a_catastrophic_pattern_gives_up_quickly_instead_of_hanging`).
- YAML parsing uses `tools.jackson.dataformat:jackson-dataformat-yaml` from the Jackson 3 line Spring Boot
  already manages (3.1.7, Apache-2.0), which brings `org.snakeyaml:snakeyaml-engine` 3.0.1 (Apache-2.0).
  Duplicate keys are refused. JSON Schema validation uses networknt `json-schema-validator`, already in
  the plan's list.
- Bundle identity is the SHA-256 of the parsed policy and tests written as key-sorted compact JSON, so
  reformatting a file does not change it. (The audit log's RFC 8785 canonicalisation arrives in Phase 5.)

## Consequences

- No "not matches" or arithmetic: limits are written as narrow allow rules plus the default deny.
- Properties are testable: `PolicyPropertiesTest` checks over 1 000 generated cases that adding a deny
  never relaxes a decision and that rule order never changes the effect.
- Measured on 2026-11-14 on the owner's machine (12 CPUs, Java 25.0.5, Docker Desktop): 200 rules,
  median 4.4 µs and p99 13.2 µs per decision (`make bench-policy`).

## Alternatives considered

CEL (Google's Java implementation): a new dependency and an expression language. OPA as a sidecar: a second
process and a network hop in every call. RE2/J for `matches`: a new dependency; the budget approach bounds
time without it.
