# ADR 0007: Approvals hold the call; the Tasks extension is future work

- Status: accepted
- Date: 2026-11-14

## Context

A `REQUIRE_APPROVAL` decision needs a person. Revision 2026-07-28 offers two protocol shapes for
long-running work (https://modelcontextprotocol.io/specification/2026-07-28/changelog,
https://modelcontextprotocol.io/extensions/tasks/overview):

- **Tasks** is now an official *extension* (`io.modelcontextprotocol/tasks`), not core. A server may
  return a task only to a client that declared the extension; the client then polls `tasks/get`.
  Its overview names approval gates as a use case.
- **MRTR** (`resultType: "input_required"`) asks the *client* for input, which would put the approval
  question in front of the agent and its model: the component the approval is meant to check.

Most clients will not declare the Tasks extension soon, and Aduana must work with any client.

## Decision

Implement the plan's **hold** mode (§5.4): the gateway keeps the `tools/call` request open for up to
`hold_timeout` (60 s by default) while a console user decides. Approved: the call continues. Rejected:
`APPROVAL_REJECTED`. No decision: a tool error `APPROVAL_PENDING` with the `approval_id`, and an
identical retry reuses the same approval through its `binding_hash`.

Support for the Tasks extension, for clients that declare it, is future work.

## Consequences

- Works with every client. A long hold can hit client or proxy timeouts, so the default stays at 60 s and
  the retry path is the normal way to finish a long approval.
- The specification says a broken stream loses the in-flight request. The retry semantics cover it: the
  approval survives and binds to the same arguments.

## Alternatives considered

MRTR elicitation: rejected (the model would see and answer the approval prompt). Tasks only: rejected
because few clients support it.

## Implementation (Phase 6)

`ApprovalService.obtain` reuses the newest approval with the same binding hash while it is usable,
otherwise creates one (`APPROVAL_CREATED`), then holds the request, re-reading the row every 500 ms and
waking at once on a decision made on this node. `aduana.approvals.hold-timeout` is 60 s by default and 3 s
in the demo stack. Consumption is `UPDATE … WHERE state = 'approved' AND expires_at > now RETURNING`, in the
same transaction as the `CALL_DECIDED` entry (`AuditLog.appendWithin`). Approvers must be console users
whose principal is in the rule's `approvers_group`; there is no separation of duties in v1.
