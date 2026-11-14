# Policy reference

A policy decides what happens to every tool call. It is a YAML file validated against
[`policy/schema/policy.schema.json`](../policy/schema/policy.schema.json), with no expression language
([ADR 5](adr/0005-declarative-policy-without-an-expression-language.md)). A **bundle** is a directory with
`policy.yaml` and `tests/*.yaml`; its identity is the SHA-256 of the canonical form of both.

```sh
aduana policy validate demo/policy     # loads the bundle: schema, ids, comparators, patterns
aduana policy test demo/policy         # runs every test case; exit 1 if one fails
make policy-test                       # the same, in a container
```

## Document

```yaml
version: 1
defaults:
  decision: deny            # required, and can only be deny
rules:
  - id: refunds-need-a-person            # unique; lowercase letters, digits and dashes
    description: Every refund is approved by finance.
    match: { ... }                        # every part must hold; an absent part matches everything
    effect: require_approval              # allow | require_approval | deny
    approval: { approvers_group: finance, expires_in: 15m }
    obligations: { redact_result: [iban] }
    reason_code: REFUND_OVER_LIMIT        # recorded with a deny; never sent to the agent
```

## How rules combine

| Rule | Behaviour |
|---|---|
| Every rule is evaluated | There is no "first match": a deny later in the file beats an allow earlier in it |
| Precedence | `deny` beats `require_approval` beats `allow` |
| Nothing matches | `deny` with reason `NO_MATCHING_RULE` |
| Obligations | The obligations of every matching `allow` and `require_approval` rule are joined; a deny has none |
| Several approval rules | The first matching rule names the approvers group; the shortest `expires_in` wins |
| No `approval` block | Group `approvers`, 15 minutes |
| A deny without `reason_code` | Reason `POLICY_DENIED` |

A `require_approval` rule is a grant through a person: scope it as narrowly as an `allow`. A rule such as
"any side-effect call with untrusted data needs approval" with no principal condition would turn a
default deny into an approval for every principal (the demo scopes it to the teams that may act).

## Match

| Part | Fields | Notes |
|---|---|---|
| `principal` | `name`, `attributes: {key: matcher}`, `groups: {any_of: [...]}` | An attribute the principal lacks never matches, even under `not_in` |
| `tool` | `server`, `name`, `side_effect`, `untrusted_source`, `sensitivity` | Flags and sensitivity come from the catalog, set by an administrator |
| `args` | list of conditions | All must hold |
| `context` | `flow_from_untrusted`, `context_tainted` | Marks computed by the gateway (plan §5.6) |

A string **matcher** is a literal (`team: support`), `{in: [a, b]}` or `{not_in: [a, b]}`.

## Argument conditions

`{ path: "<path>", <comparator>: <operand>, any: <bool> }` with exactly one comparator.

| Comparator | Holds when the value… |
|---|---|
| `eq`, `ne` | equals / differs from a string, number or boolean (numbers by value: `5` equals `5.0`) |
| `in`, `not_in` | is / is not one of a list |
| `gt`, `gte`, `lt`, `lte` | compares with a number |
| `matches` | is a string in which the pattern finds a match (Java syntax, at most 256 characters) |
| `exists` | is present (`true`) or absent (`false`) |
| `length_lte` | is a string of at most N code points, or an array of at most N elements |

**Paths** are a subset of JSONPath: `$`, `.field`, `[n]`, `[*]`. Through `[*]` the condition must hold for
every element, or for at least one with `any: true`. A path that selects nothing does not match (except
`exists: false`), so `[*]` over an empty array does not match.

**Types**: a value of the wrong type (a string compared with `gt`) does not match, and the rule is reported
as `POLICY_TYPE_MISMATCH` in the decision. When a rule names one exact tool, the load also checks each
top-level field against that tool's pinned input schema and refuses impossible comparisons.

**Patterns** are bounded ([ADR 5](adr/0005-declarative-policy-without-an-expression-language.md)): no
back-references or look-around, inputs over 4096 characters never match, and a match that reads more than
200 000 characters is abandoned and counts as no match. There is no "does not match" comparator: write the
allowed shape as a narrow allow rule and let the default deny the rest.

## Obligations

Detector ids: `email`, `phone_es`, `dni_nie`, `iban`, `card`, `secret` (plan §5.7).

| Obligation | Effect |
|---|---|
| `redact_result` | Matches in the tool's result are replaced by `[REDACTED:<id>]` before the agent sees them |
| `deny_args_containing` | The call is denied if the arguments contain a match |
| `flag_args_containing` | The call is flagged in the log, not stopped |

Obligations are parsed and joined today; the gateway applies them from Phase 7.

## Test cases

```yaml
- name: a support agent cannot refund on its own
  given:
    principal: { name: agent-1, attributes: { team: support }, groups: [] }
    tool: { server: payments, name: refund, side_effect: true, untrusted_source: false, sensitivity: high }
    args: { amount_cents: 1000, order_id: "A1", destination_iban: XX79…, reason: x }
    context: { flow_from_untrusted: false, context_tainted: false }
  expect:
    decision: require_approval
    rules: [refunds-need-a-person]        # the exact set of matching rules, in any order
    reason_code: …                        # for a deny
    redact_result: [iban]                 # the joined redaction obligation
```

Absent fields default to: principal `test-principal` with no attributes or groups, tool flags `false`,
sensitivity `low`, empty arguments, no marks. Test files are part of the bundle's identity.

## Loading in the gateway

`POST /api/v1/policy` (administrators) or `aduana.policy.directory` at start-up. A bundle is activated only
if it validates, every one of its test cases passes and the type check against the catalog finds nothing;
otherwise the active bundle stays and `POLICY_REJECTED` is recorded. With no bundle ever loaded, every call
is denied. `tools/list` shows a tool only when some allow or approval rule could match the caller and the
tool, and no argument-free deny rule always matches them.
