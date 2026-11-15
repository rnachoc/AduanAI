# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Builds replay/index.html: the demo scenarios as Aduana logged them, replayable without a server.

Usage: python scripts/build_replay.py <entries.jsonl> <out.html> [--gateway URL] [--agent NAME] [--last N]

The input is an export of the log (`make audit-export`). With `--gateway`, the script signs in with
ADUANA_ADMIN_EMAIL and ADUANA_ADMIN_PASSWORD to name agents and keeps only the agent called
`--agent` (support-agent by default); without it, every agent's calls are kept. Each step is one
call (the decision joined with how it ended) or one approval decision. The page is a single file
with its data, styles and script inline, so it works opened from disk and on GitHub Pages. The log
holds arguments redacted, so the page does too.

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

from __future__ import annotations

import argparse
import html
import json
import os
import sys
from pathlib import Path

ENDINGS = {"CALL_COMPLETED", "CALL_FAILED"}


def steps(entries: list[dict[str, object]], agents: set[str] | None) -> list[dict[str, object]]:
    """Turns log entries into replay steps for the chosen agents (all when None).

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    result: list[dict[str, object]] = []
    open_calls: dict[tuple[object, object], dict[str, object]] = {}
    approvals: set[object] = set()
    for item in entries:
        kind = item.get("type")
        principal = item.get("principal_id")
        chosen = agents is None or principal in agents
        if kind == "CALL_DECIDED" and chosen:
            step = {
                "kind": "call",
                "seq": item["seq"],
                "ts": item["ts"],
                "tool": item.get("tool"),
                "arguments": item.get("args_redacted"),
                "decision": item.get("decision"),
                "reason": item.get("reason_code"),
                "rules": item.get("rule_ids") or [],
                "flags": list(item.get("flags") or []),
                "outcome": "not forwarded" if item.get("decision") != "allow" else "",
            }
            result.append(step)
            if item.get("approval_id"):
                approvals.add(item["approval_id"])
            if item.get("decision") == "allow" or item.get("approval_id"):
                open_calls[(principal, item.get("tool"))] = step
        elif kind in ENDINGS and chosen:
            step = open_calls.pop((principal, item.get("tool")), None)
            if step is not None:
                if kind == "CALL_COMPLETED":
                    step["outcome"] = f"completed in {item.get('latency_ms')} ms"
                else:
                    step["outcome"] = f"failed: {item.get('reason_code')}"
                for flag in item.get("flags") or []:
                    if flag not in step["flags"]:
                        step["flags"].append(flag)
        elif kind == "APPROVAL_DECIDED" and item.get("approval_id") in approvals:
            result.append(
                {
                    "kind": "approval",
                    "seq": item["seq"],
                    "ts": item["ts"],
                    "tool": item.get("tool"),
                    "arguments": None,
                    "decision": str(item.get("reason_code") or "decided").lower(),
                    "reason": item.get("reason_code"),
                    "rules": [],
                    "flags": [],
                    "outcome": "a person decided the held call",
                }
            )
    return result


def render(replay_steps: list[dict[str, object]], title: str) -> str:
    """Renders the self-contained page.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    data = json.dumps(replay_steps, ensure_ascii=False).replace("</", "<\\/")
    return PAGE.replace("{{TITLE}}", html.escape(title)).replace("{{DATA}}", data)


PAGE = (Path(__file__).resolve().parent / "replay_template.html").read_text(encoding="utf-8")


def agent_ids(gateway: str, name: str) -> set[str]:
    """Signs in to the gateway and returns the ids of the agents with that name.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from demo_seed import Api

    api = Api(gateway)
    api.call(
        "POST",
        "/api/v1/auth/login",
        {"email": os.environ["ADUANA_ADMIN_EMAIL"], "password": os.environ["ADUANA_ADMIN_PASSWORD"]},
    )
    api.call("GET", "/api/v1/auth/me")
    principals = api.call("GET", "/api/v1/principals")
    return {str(p["id"]) for p in principals if isinstance(p, dict) and p.get("name") == name}  # type: ignore[union-attr]


def main(arguments: list[str]) -> int:
    """Reads the export, builds the steps and writes the page.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("entries")
    parser.add_argument("output")
    parser.add_argument("--gateway")
    parser.add_argument("--agent", default="support-agent")
    parser.add_argument("--last", type=int, default=40, help="keep only the newest steps")
    options = parser.parse_args(arguments)
    entries = [
        json.loads(line) for line in Path(options.entries).read_text(encoding="utf-8").splitlines() if line.strip()
    ]
    agents = agent_ids(options.gateway, options.agent) if options.gateway else None
    replay_steps = steps(entries, agents)[-options.last :]
    title = f"{len(replay_steps)} steps of the demo scenarios, as `{options.agent}` ran them through Aduana"
    Path(options.output).parent.mkdir(parents=True, exist_ok=True)
    Path(options.output).write_text(render(replay_steps, title), encoding="utf-8")
    print(f"build_replay: {len(replay_steps)} steps written to {options.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
