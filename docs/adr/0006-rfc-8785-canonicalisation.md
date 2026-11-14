# ADR 0006: RFC 8785 canonicalisation, written in-house

- Status: accepted
- Date: 2026-11-14

## Context

Entry hashes must mean the same thing to every verifier, so the bytes that are hashed must be defined
independently of any JSON library. RFC 8785 (JCS) does that. The candidate library,
`io.github.erdtman:java-json-canonicalization` 1.1 (Apache-2.0), was last released in 2018 and works on
its own JSON parser, not on Jackson trees.

## Decision

Write JCS in the `audit` module (`Jcs`, about 150 lines including comments) on Jackson 3 trees, and again in
Python for the independent verifier. Numbers use Java's shortest round-trip `Double.toString` digits
(JDK 19+) laid out as ECMAScript does; Java prints at least two digits (`4.9E-324`), so when one digit
already round-trips it is used (`5e-324`), as ECMAScript does. Both implementations pass every vector of
RFC 8785 Appendix B, the sorting example of §3.2.3 and the byte dump of §3.2.4
(https://www.rfc-editor.org/rfc/rfc8785.txt, read 2026-11-14).

## Consequences

No dependency, and the Java and Python verifiers agree on a real export (`verifier/testdata`). Integers
above 2^53 lose precision when hashed, as RFC 8785 requires; Aduana's own integer fields are far below it.

## Alternatives considered

The 2018 library: a dependency with its own parser. Hashing Jackson's sorted output: not a standard a third
party can reimplement exactly.
