# ADR 0020: Dependencies of the console

- Status: accepted
- Date: 2026-11-15

## Context

Plan §4.3 sets Angular (current stable, standalone components, no UI library, own CSS, one typeface served
from the repository) and Playwright for five flows; Phase 9 asks for axe-core (licence to be checked) and
70 % line coverage. The owner asked for this session to proceed without questions, so the choice is
recorded here for review.

## Decision

Runtime dependencies (versions checked on https://registry.npmjs.org on 2026-11-14):

| Package | Version | Licence |
|---|---|---|
| `@angular/common`, `compiler`, `core`, `forms`, `platform-browser`, `router` | 22.2.6 | MIT |
| `rxjs` | 7.8.2 | Apache-2.0 |
| `tslib` | 2.9.1 | 0BSD |

Development dependencies: `@angular/build` and `@angular/cli` 22.2.7, `@angular/compiler-cli` 22.2.6,
`vitest` and `@vitest/coverage-v8` 5.1.0, `jsdom` 30.1.3, `typescript` 6.0.3, `eslint` 10.15.0,
`@eslint/js` 10.0.1, `typescript-eslint` 8.72.2, `prettier` 3.10.1 (all MIT or Apache-2.0),
`@playwright/test` 1.65.1 (Apache-2.0), `@axe-core/playwright` 4.14.0 and `axe-core` 4.14.0 (MPL-2.0).

MPL-2.0 is file-level copyleft: it applies to changes to axe-core's own files, which this project does not
make, and axe-core runs only in the test browser, never in the shipped image. It is not GPL, AGPL or SSPL.

The typeface is IBM Plex Sans 400 and 600, latin subset, taken from `@fontsource/ibm-plex-sans` 5.3.0
and committed under `console/public/fonts/` with its SIL Open Font License (`OFL.txt`); it is not an npm
dependency of the build.

Playwright runs in `mcr.microsoft.com/playwright:v1.65.1-noble`, pinned by digest in `scripts/images.env`.

## Consequences

The console's shipped JavaScript is Angular, RxJS and tslib only (about 330 kB raw, 86 kB compressed,
as `ng build` reports). Components carry no styles, so the content security policy needs no
`'unsafe-inline'`; all styling is in `src/styles.css`.

## Alternatives considered

A UI component library: the plan rules it out. Jest or Karma for unit tests: Angular 22's default
builder is Vitest. Pa11y instead of axe: axe is what the plan names.
