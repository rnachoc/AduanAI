# Phase 0 evidence

Commands run on 2026-11-14 on the owner's Windows 11 machine (Docker Desktop 29.8.2).

## CA0.3: an AGPL dependency fails the licence gate

`com.itextpdf:itextpdf:5.5.13` (AGPL-3.0) was added to `cli/pom.xml` on purpose, then removed.

```
$ sh scripts/check_licenses.sh
licence not allowed: com.itextpdf:itextpdf:5.5.13: GNU Affero General Public License v3
check_licenses: 53 Maven lines, 0 npm lock files, 1 problems
```

The same rule is covered permanently by `scripts/test_check_licenses.py`
(`test_an_agpl_dependency_fails`).

## CA0.1: `make up && make smoke`

```
$ make up
 Container aduana-postgres-1 Healthy
 Container aduana-gateway-1 Healthy
$ make smoke
smoke: gateway healthy
```

Host note: this machine has Docker but no GNU make, so `make` was run from a container
(`docker:29-cli` plus `make`, Docker socket mounted, repository mounted at the path Docker
Desktop maps to the host). The Makefile itself only calls `docker`, `git` and `sh`.
The default host port is 8780 because 8080 was taken by another local project.

## CA0.2: removing an author comment fails lint

The `@author` line was deleted from `PolicyModule`'s Javadoc, then restored:

```
$ python scripts/check_authorship.py
.../PolicyModule.java: class PolicyModule has no Javadoc naming the author
check_authorship: 18 files, 1 problems        (exit 1)
```

Permanent proof: `scripts/test_check_authorship.py`
(`test_removing_the_author_from_a_class_fails` and five more cases).

## CA0.4: a commit with a real-format key is refused

A file holding a random `ghp_` token (GitHub personal access token format) was staged:

```
$ git commit -m "chore: try to commit a token"
WRN leaks found: 1                             (exit 1, nothing committed)
```
