# Phase 9 evidence

Run on 2026-11-15.

```
$ make test-console      20 tests, 0 failures; lines 95.3 % (467/490), threshold 70 %
                         ng build: main 328.27 kB raw, 85.89 kB estimated transfer
$ make test-gateway      94 tests, 0 failures (ConsoleServingTest 3, ApprovalTest +1); lines 90.92 %
$ make lint              passed (console: tsc, ESLint, Prettier; licences: 2 npm lock files, 0 problems)
$ make up && make e2e    4 rounds: all steps passed
$ make console-e2e       9 passed (26.9s)
  1. sign in: wrong password refused, right one opens live activity, nothing in storage
  2. approve: finance approves a held refund to a test account
  3. reject: finance rejects a held refund and the agent is told
  4. review: an admin sees a changed tool description as a diff and accepts it
  5. search the log: refunds that needed approval, and one entry in full
  a viewer sees no approvals and the API refuses an approval by hand
  the console is served with a same-origin content security policy
  axe finds no critical problems on the six screens (light)
  axe finds no critical problems on the six screens (dark)
```

The axe check fails on `critical` and `serious` violations, stricter than the plan's "no critical errors".
Its first run found `scrollable-region-focusable` on the policy screen; scrollable `<pre>` blocks are now
focusable.

Screenshots in `docs/images/` were taken by `make console-e2e` on the stack after `make demo`:
`console-activity.png`, `console-approvals.png`, `console-log.png`, `console-catalog-review.png`,
`console-policy.png`, `console-identities.png`.

Two defects found by the flows and fixed: the activity stream showed "Connecting…" until the first new
entry (Spring sends no bytes for an idle `SseEmitter`; the gateway now sends a comment on connect), and
the approval queue listed approvals of other groups that the user could not decide (now filtered by the
user's groups, `the_queue_lists_only_approvals_the_user_may_decide`).
