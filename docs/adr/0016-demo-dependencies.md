# ADR 0016: Dependencies of the demo environment

- Status: accepted
- Date: 2026-11-14

## Context

Plan §4.3 lists "TypeScript + official MCP SDK for TypeScript" for `demo/` and ESLint + Prettier for
TypeScript quality. Scenarios are YAML files (§7), and the SDK's own API takes Zod schemas. The plan says
to stop and ask before adding a dependency not on the list; the owner asked for this session to proceed
without questions, so the decision is recorded here for review.

## Decision

Runtime dependencies (all checked on https://registry.npmjs.org on 2026-11-14):

| Package | Version | Licence | Why |
|---|---|---|---|
| `@modelcontextprotocol/server` | 2.4.0 | Apache-2.0 | Official SDK, server side |
| `@modelcontextprotocol/client` | 2.4.0 | Apache-2.0 | Official SDK, used by the scripted agent |
| `@modelcontextprotocol/node` | 2.1.2 | Apache-2.0 | Official adapter from the SDK to `node:http` |
| `zod` | 4.6.7 | MIT | Already a dependency of the SDK; tool input schemas and scenario validation |
| `yaml` | 2.9.1 | ISC | Parses scenario files |

Development dependencies: `typescript` 6.0.3 (Apache-2.0), `@types/node` 24.19.2 (MIT), `eslint` 10.15.0,
`@eslint/js` 10.0.1, `typescript-eslint` 8.72.2, `prettier` 3.10.1 (all MIT).

TypeScript 7.0.2 is the latest release, but `typescript-eslint` 8.72.2 declares
`typescript: ">=4.8.4 <6.1.0"` as its peer range, so the latest 6.0 release is used.

Tests use `node:test`, which needs no dependency.

## Consequences

`scripts/check_licenses.sh` reads `demo/package-lock.json` and checks every non-development package.
Moving to TypeScript 7 waits for `typescript-eslint` support.

## Alternatives considered

JSON scenarios with no parser dependency: rejected, the plan names YAML. A hand-written YAML subset parser:
more code to test than the dependency is worth.
