# Contributing

Aduana is built test first and every rule below is enforced by `make lint` or a git hook.

1. Install Docker and GNU make. Nothing else is needed.
2. Run `make hooks` once, so the `pre-commit` and `commit-msg` hooks run.
3. Work on a branch. Commit the failing test first (`test(scope): ...`), then the code
   (`feat(scope): ...` or `fix(scope): ...`), then the docs (`docs: ...`).
4. Before you push, run `make lint` and `make test`.

## Rules the build enforces

- Conventional Commits subjects, lowercase, no full stop, at most 72 characters
  (`scripts/check_commits.py`).
- No co-author or tool-attribution trailers in commit messages.
- Every Java type and method, every TypeScript class, method and exported function, and every
  Python module, class and function names its author in its doc comment
  (`scripts/check_authorship.py`).
- Every source file starts with the SPDX licence header.
- No dependency without a permissive licence (`scripts/check_licenses.sh`).
- No secrets anywhere in the history (gitleaks).
- Container images pinned by digest (`scripts/check_pinned_images.py`).
