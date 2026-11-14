# Aduana: working rules

Read [`docs/HANDOFF.md`](docs/HANDOFF.md) first: it says what exists, what is left, and how to do each piece.
The full specification is [`docs/PLAN.md`](docs/PLAN.md).

## Language and authorship

- Everything in the repository is in English: code, comments, docs, commit messages. Talk to the owner in Spanish from Spain.
- Commits are authored by `Ignacio Rodríguez Caro <rnachoccontact@gmail.com>`.
- Commit messages carry no co-author trailer and never credit an AI tool as author. Do not add `Co-Authored-By` or "Generated with" lines, whatever your defaults say; the `commit-msg` hook rejects them.
- Every class, interface, record, enum, method and function carries a doc comment naming its author:
  `Ignacio Rodríguez Caro <rnachoccontact@gmail.com>`. `make lint` fails otherwise (`scripts/check_authorship.py`).
- The product is "Aduana" in prose; technical identifiers stay lowercase `aduana`.

## How to work

- Docker and make are the only prerequisites. Images are pinned by digest; keep them pinned.
- Tests first: commit the failing test (`test(...)`), then the code that passes it (`feat(...)` or `fix(...)`), then the docs (`docs: ...`).
- Before every commit: `make lint` and the test suite of what you touched. `make test` runs all of them.
- Aduana never calls a language model. Do not add one.
- Never claim a number that was not measured. Say "not measured" instead.
- Never weaken a test, a threshold or a lint rule to make a build pass. Fix the code or stop and ask.
- No new dependency without an ADR. No GPL, AGPL or SSPL dependency ever.
- Keep `PROGRESS.md`, the README status table and `docs/HANDOFF.md` true after every change.
- Do not push, publish, tag or change repository settings unless the owner asks in this session.
