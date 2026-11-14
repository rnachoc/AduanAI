# Security policy

## Reporting a vulnerability

Email `rnachoccontact@gmail.com` with the subject `Aduana security`. Include the version or
commit, the steps to reproduce and the impact you expect. Do not open a public issue.

You get an acknowledgement within 5 working days. A fix or a mitigation plan follows within
30 days for issues that break a deterministic guarantee: default deny, the append-only audit
log, fail-closed forwarding, content-bound approvals or credential isolation.

## Scope

In scope: the gateway, the CLI, the console, the verifier and the published container image.
Out of scope: the demo servers in `demo/`, which are deliberately vulnerable test fixtures.

## Supported versions

Only the latest released version receives security fixes. No version has been released yet.
