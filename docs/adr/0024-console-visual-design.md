# ADR 0024: The console's visual design

- Status: accepted
- Date: 2026-11-15

## Context

Plan Phase 9 asked for a sober design: one typeface served from the repository, light and dark themes,
usable with the keyboard. After Phase 12 the owner asked for a redesign in the manner of large consumer
sites (Apple, Netflix, HBO): a console that is pleasant to look at in a demo and in screenshots.

## Decision

- Borrow their common traits, not their brands: dark surfaces as the designed-for theme with a light
  alternative, large type with tight letter spacing for headings, generous space, rounded cards, a
  translucent blurred header, pill-shaped navigation and buttons, one warm amber accent. No logo, colour
  palette or typeface of those companies.
- Keep every Phase 9 constraint: IBM Plex Sans from the repository is still the only typeface; all
  styling stays in `src/styles.css` with design tokens as CSS variables, so the content security policy
  needs no inline styles; no UI library; axe finds no critical or serious problem on any screen in
  either theme; reduced-motion users get no transitions.
- Structure: a full-screen sign-in hero; on every screen a header with an eyebrow line, the title and one
  sentence of purpose; summary tiles on Activity; tables inside panels; detail panes open beside a list
  only when something is selected.

## Consequences

The console looks less like an admin tool and more like a product. The screenshots in `docs/images/`
and the README were regenerated with `make console-e2e`.

## Alternatives considered

Keeping the sober Phase 9 look: it was the plan, but the owner asked for the change.
