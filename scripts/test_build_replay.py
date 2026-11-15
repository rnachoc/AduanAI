# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Tests for the static replay page built from an exported log.

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_replay as replay

AGENT = "11111111-1111-1111-1111-111111111111"
OTHER = "22222222-2222-2222-2222-222222222222"


def entry(seq: int, kind: str, principal: str | None = AGENT, **fields: object) -> dict[str, object]:
    """Builds a log entry with the fields the replay reads.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    base: dict[str, object] = {
        "seq": seq,
        "ts": f"2026-11-15T10:00:{seq:02d}.000000Z",
        "type": kind,
        "principal_id": principal,
        "tool": None,
        "args_redacted": None,
        "decision": None,
        "rule_ids": [],
        "reason_code": None,
        "approval_id": None,
        "actor_id": None,
        "flags": [],
        "latency_ms": None,
    }
    base.update(fields)
    return base


ENTRIES = [
    entry(1, "POLICY_LOADED", None),
    entry(
        2,
        "CALL_DECIDED",
        tool="webfetch__fetch_url",
        args_redacted={"url": "https://blog.example.com/x"},
        decision="allow",
    ),
    entry(3, "CALL_COMPLETED", tool="webfetch__fetch_url", latency_ms=12, flags=["INJECTION_PATTERN"]),
    entry(
        4,
        "CALL_DECIDED",
        tool="payments__refund",
        args_redacted={"destination_iban": "[REDACTED:iban:XX20…6001]", "note": "</script><script>alert(1)</script>"},
        decision="require_approval",
        rule_ids=["untrusted-flow-into-side-effects"],
        flags=["FLOW_FROM_UNTRUSTED"],
        approval_id="a-1",
        reason_code="APPROVAL_PENDING",
    ),
    entry(5, "CALL_DECIDED", OTHER, tool="crm__get_customer", decision="allow"),
    entry(6, "APPROVAL_DECIDED", tool="payments__refund", approval_id="a-1", reason_code="REJECTED", actor_id="u-1"),
]


class StepTests(unittest.TestCase):
    """Turning entries into replay steps.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    def test_calls_are_joined_with_their_outcome(self) -> None:
        """A decision and the completion that follows it become one step.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        steps = replay.steps(ENTRIES, {AGENT})
        self.assertEqual([step["seq"] for step in steps], [2, 4, 6])
        self.assertEqual(steps[0]["outcome"], "completed in 12 ms")
        self.assertIn("INJECTION_PATTERN", steps[0]["flags"])
        self.assertEqual(steps[1]["decision"], "require_approval")
        self.assertEqual(steps[2]["kind"], "approval")
        self.assertEqual(steps[2]["decision"], "rejected")

    def test_other_agents_are_left_out(self) -> None:
        """Only the chosen agents' calls are replayed.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        self.assertNotIn(5, [step["seq"] for step in replay.steps(ENTRIES, {AGENT})])


class PageTests(unittest.TestCase):
    """The generated page.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    def test_the_page_needs_no_server_and_no_network(self) -> None:
        """Everything is inline: no external script, style, font or fetch.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        page = replay.render(replay.steps(ENTRIES, {AGENT}), "test recording")
        self.assertNotIn("<script src", page)
        self.assertNotIn("<link", page)
        self.assertNotIn("fetch(", page)
        self.assertNotIn("XMLHttpRequest", page)
        self.assertIn('id="data"', page)

    def test_data_cannot_close_the_script_element(self) -> None:
        """A value containing `</script>` stays inside the JSON block.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        page = replay.render(replay.steps(ENTRIES, {AGENT}), "test recording")
        data = page.split('<script type="application/json" id="data">', 1)[1].split("</script>", 1)[0]
        steps = json.loads(data)
        self.assertEqual(len(steps), 3)
        self.assertIn("alert(1)", json.dumps(steps))


if __name__ == "__main__":
    unittest.main()
