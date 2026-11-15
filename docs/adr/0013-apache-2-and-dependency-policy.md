# ADR 0013: Apache-2.0 and the dependency policy

- Status: accepted
- Date: 2026-11-15

## Context

Aduana is meant to be used and extended by companies, including commercially. Its dependencies decide
what users may do with it.

## Decision

- Aduana is licensed Apache-2.0 (`LICENSE`, `NOTICE`); every source file carries an SPDX header.
- No GPL, AGPL or SSPL dependency, at any version, in anything shipped. Weak copyleft limited to its own
  files (MPL-2.0, EPL-2.0) is allowed; ADR 20 records the one case (axe-core, test-only).
- `make lint` runs `scripts/check_licenses.sh`: every Maven runtime dependency and every npm package in
  a lock file must have an allowed licence, or a reasoned entry in `build-config/license-exceptions.txt`.
  Development-only npm packages are not shipped and are not checked.
- A new dependency needs an ADR naming its version, licence and why (ADR 16, 18, 20, 22).
- Container images are pinned by digest (`scripts/images.env`, `scripts/check_pinned_images.py`).

## Consequences

Some useful libraries are out of reach. Adding a dependency is slower by one document, which is the
point.

## Alternatives considered

AGPL for the gateway, to keep hosted forks open: it would keep out exactly the companies the product
is for.
