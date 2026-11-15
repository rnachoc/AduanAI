# Video script (2 minutes)

Screen recording of the demo stack after `make up` and `make demo`, with a voice-over. Times are
targets.

| Time | Screen | Voice-over |
|---|---|---|
| 0:00–0:10 | README title and the line "The model proposes, the code decides." | "Agents call tools. Aduana sits in between and decides, in code, which calls go through." |
| 0:10–0:25 | Terminal: `git clone`, `make up`, `make demo`; containers turn healthy | "Docker and make, three commands. The demo has a CRM, a payments service and a web fetcher, and a scripted support agent." |
| 0:25–0:45 | Replay page: the blog post fetched, the injection marks, the big refund denied | "The agent reads a web page with instructions hidden for agents. It asks for a full refund to the attacker's account. A rule denies refunds over 500 euros, whatever the prompt said." |
| 0:45–1:05 | Console, Approvals as security: the 49.99 EUR refund held, IBAN redacted to `XX20…6001`, flag `FLOW_FROM_UNTRUSTED` with its source entry | "A smaller refund to the same account. Aduana saw that IBAN in the untrusted page, so the call waits for security. Finance cannot release it." |
| 1:05–1:20 | Console, Log: the chain is intact, last checkpoint; terminal: `python verifier/verify_chain.py …` prints "intact" | "Every decision is logged before anything is forwarded, chained by hash and signed. Anyone can verify it with a short Python script." |
| 1:20–1:35 | Console, Simulation: paste a stricter rule, 27 decisions would change, the unknown ones listed | "Before loading a new policy, replay it over what happened. Decisions that depend on redacted data are shown as unknown, not as unchanged." |
| 1:35–1:50 | `attacks/REPORT.md`: deterministic cases all stopped, the heuristic escapes listed | "Ninety-one attacks run against the stack on every change. Rules stop every case they cover; the heuristics show their real rate, escapes included." |
| 1:50–2:00 | README "What it does not do" | "What it does not do is written down too. Apache-2.0." |

Numbers on screen come from the reports as generated; re-record those shots if a report changes.
