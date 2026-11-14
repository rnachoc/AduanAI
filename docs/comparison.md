# How Aduana compares

Checked on **2026-11-14** against each project's own documentation and repository, not against
comparison articles. "Not found" means the pages listed did not describe the feature; it does not
prove the feature is absent. Corrections are welcome through an issue.

## The four things Aduana combines

1. A tamper-evident log that a third party can verify without trusting the gateway (hash chain,
   signed checkpoints, an independent verifier).
2. Policies tested as code, with simulation of a candidate policy against recorded traffic.
3. Approvals bound to the exact argument hash, single use and expiring.
4. Tracking of untrusted data from a tool result into the arguments of a later side-effecting call.

| | Obot | MCPX (Lunar.dev) | Docker MCP Gateway | agentgateway | Aduana |
|---|---|---|---|---|---|
| Licence | MIT [1] | MIT, "free for non-production/personal use" per README [5] | MIT [8] | Apache-2.0 [11] | Apache-2.0 |
| Audit log of tool calls | Yes: request, response, user, timestamp, server; JSONL export to S3/GCS/Azure [2][3] | Not documented; control plane shows live traffic [5][6] | `--log-calls` on by default; tool name and argument shape, not values [9][10] | Structured access logs with CEL filters, OTLP export, optional SQLite/PostgreSQL store [12] | Hash-chained entries for every decision and admin change |
| Tamper evidence / independent verifier | Not found [2][3] | Not found | Not found [10] | Not found [12] | Signed checkpoints, a stdlib-only verifier under 200 lines |
| Policy language | Guardrail filters with optional CEL conditions, filter servers, webhooks; set in the UI [4] | JSON configuration; ACLs mentioned in the changelog [6][7] | Tool allow lists per profile; YAML config [8][9] | CEL in YAML or UI; external authz (OPA) [13][14] | Declarative YAML without an expression language |
| Policy tests / simulation on recorded traffic | Manual testing described [4]; simulation not found | Not found | `--dry-run` checks start-up only [9] | CEL playground in the UI [13]; simulation not found | Test cases next to the policy; simulation over the log |
| Human approval of a tool call | Not found [4] | Not found | Not built in; before/after interceptors exist [9] | Not built in; external server may deny [14] | Hold or retry, bound to argument hash, single use, expiring |
| Untrusted data flow (taint) | Not found [4] | Not found | Prompt injection and description poisoning listed as out of scope [10] | Not found; response-phase masking only [13] | Literal-value taint by hash, with a stated limit |
| Identity | OIDC providers, Okta SCIM, roles [1][2] | OIDC on backends per changelog [6] | Bearer token on HTTP transports by default [10] | JWT, OIDC, MCP OAuth, token exchange [12] | API keys, optional OIDC JWT |
| Isolation of MCP servers | Hosted servers run as Docker or Kubernetes workloads with egress rules [1] | Not found | Strongest: per-server containers, no-new-privileges, CPU/memory limits, network blocking, image signature checks [10][15] | Not found | Not a goal: stdio servers run as allow-listed child processes |
| Rate limits | Not found for MCP | Lunar Proxy README mentions them [5] | Not found | Per-key budgets and limits [12] | Per principal and per tool, plus a daily budget |
| Tool definition pinning | Snapshot pinning of catalog entries [1] | Not found | Not found | Not found | Hash per definition; a change hides the tool until reviewed |

**Verdict on 2026-11-14:** none of the four documents all four of Aduana's combined points.
None documents a hash chain, signed checkpoints or a verifier, approvals bound to argument hashes,
or taint tracking. Each is stronger than Aduana somewhere: Docker on container isolation, Obot on
identity and hosted-server management, agentgateway on protocol breadth and token exchange.

Limitation of this table: `docs.lunar.dev` redirected to `help.boomi.com` on 2026-11-14, so the MCPX
column rests on its GitHub repository only.

## Sources (all read 2026-11-14)

1. https://github.com/obot-platform/obot
2. https://docs.obot.ai/security/audit-data/
3. https://docs.obot.ai/configuration/audit-log-export/
4. https://docs.obot.ai/functionality/filters/
5. https://github.com/TheLunarCompany/lunar
6. https://github.com/TheLunarCompany/lunar/blob/main/mcpx/CHANGELOG.md
7. https://github.com/TheLunarCompany/lunar/blob/main/mcpx/README.md
8. https://github.com/docker/mcp-gateway
9. https://github.com/docker/mcp-gateway/blob/main/docs/generator/reference/mcp_gateway_run.md
10. https://github.com/docker/mcp-gateway/blob/main/docs/security.md
11. https://github.com/agentgateway/agentgateway
12. https://agentgateway.dev/docs/standalone/latest/documentation/mcp/mcp-observability.md
13. https://agentgateway.dev/docs/standalone/latest/documentation/mcp/guardrails/cel.md
14. https://agentgateway.dev/docs/standalone/latest/documentation/configuration/security/mcp-authz/
15. https://docs.docker.com/ai/mcp-catalog-and-toolkit/toolkit/
