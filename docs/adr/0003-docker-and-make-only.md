# ADR 0003: Docker and make are the only prerequisites

- Status: accepted
- Date: 2026-11-14

## Context

Reviewers and buyers judge a security tool by whether it runs on their machine in minutes.
Asking them to install a JDK, Maven, Node and Python at the right versions loses most of them.

## Decision

Every `make` target runs its tool in a container pinned by digest: Maven builds and tests run in the
Temurin JDK image with a named volume for the Maven cache; Python checks run in the Python image or in
the image built from `build-config/lint/Dockerfile`; gitleaks and editorconfig-checker run from their
official images. Integration tests reach Docker through the mounted socket (Testcontainers).

The git hooks prefer a local `python` when there is one, for speed, and fall back to the container.

## Consequences

- First runs download images and dependencies; later runs reuse the `aduana-m2` volume.
- On Windows, Git Bash rewrites container paths unless `MSYS_NO_PATHCONV=1`; the Makefile exports it.
- `make` itself must exist on the host. On the development machine it was run from a container
  (`docker:29-cli` with `make`) because the host has none; see `docs/evidence/phase-00.md`.

## Alternatives considered

A dev container or Nix: both add a third prerequisite. Local toolchains with version files: drift between machines.
