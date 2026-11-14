# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Tests for the independent verifier: RFC 8785 vectors, a real export, and the six manipulations.

testdata/ holds an export taken from a running gateway (make audit-export), so these tests check
that two implementations written apart agree on real data.

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

import ast
import copy
import json
import struct
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import verify_chain as verifier

HERE = Path(__file__).resolve().parent
ENTRIES = verifier.read_lines(str(HERE / "testdata" / "entries.jsonl"))
CHECKPOINTS = verifier.read_lines(str(HERE / "testdata" / "checkpoints.jsonl"))

NUMBERS = {
    "0000000000000000": "0",
    "8000000000000000": "0",
    "0000000000000001": "5e-324",
    "8000000000000001": "-5e-324",
    "7fefffffffffffff": "1.7976931348623157e+308",
    "ffefffffffffffff": "-1.7976931348623157e+308",
    "4340000000000000": "9007199254740992",
    "c340000000000000": "-9007199254740992",
    "4430000000000000": "295147905179352830000",
    "44b52d02c7e14af5": "9.999999999999997e+22",
    "44b52d02c7e14af6": "1e+23",
    "44b52d02c7e14af7": "1.0000000000000001e+23",
    "444b1ae4d6e2ef4e": "999999999999999700000",
    "444b1ae4d6e2ef4f": "999999999999999900000",
    "444b1ae4d6e2ef50": "1e+21",
    "3eb0c6f7a0b5ed8c": "9.999999999999997e-7",
    "3eb0c6f7a0b5ed8d": "0.000001",
    "41b3de4355555553": "333333333.3333332",
    "41b3de4355555554": "333333333.33333325",
    "41b3de4355555555": "333333333.3333333",
    "41b3de4355555556": "333333333.3333334",
    "41b3de4355555557": "333333333.33333343",
    "becbf647612f3696": "-0.0000033333333333333333",
    "43143ff3c1cb0959": "1424953923781206.2",
}


def problems(entries: list, checkpoints: list | None = None) -> list[str]:
    """Runs the verifier and keeps only the problem kinds.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    return [problem.split()[1] for problem in verifier.verify(entries, checkpoints or [])]


class CanonicalTests(unittest.TestCase):
    """The RFC 8785 test vectors.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    def test_appendix_b_numbers(self) -> None:
        """Every number of Appendix B serialises as the RFC says.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        for bits, expected in NUMBERS.items():
            with self.subTest(bits=bits):
                value = struct.unpack(">d", bytes.fromhex(bits))[0]
                self.assertEqual(verifier.number(value), expected)
        with self.assertRaises(ValueError):
            verifier.number(float("nan"))

    def test_the_rfc_example(self) -> None:
        """The example of section 3.2.4 produces the RFC's output.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        source = (
            '{"numbers": [333333333.33333329, 1E30, 4.50, 2e-3, 0.000000000000000000000000001],'
            ' "string": "\\u20ac$\\u000F\\u000aA\'\\u0042\\u0022\\u005c\\\\\\"\\/",'
            ' "literals": [null, true, false]}'
        )
        expected = (
            '{"literals":[null,true,false],"numbers":[333333333.3333333,1e+30,4.5,0.002,1e-27],'
            '"string":"€$\\u000f\\nA\'B\\"\\\\\\\\\\"/"}'
        )
        self.assertEqual(verifier.canonical(json.loads(source, parse_int=float)), expected)

    def test_keys_sort_by_utf16_code_units(self) -> None:
        """Section 3.2.3: an emoji (a surrogate pair) sorts before U+FB33.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        keys = ["€", "\r", "דּ", "1", "\U0001f600", "\u0080", "ö"]
        text = verifier.canonical({key: 0 for key in keys})
        self.assertEqual(list(json.loads(text)), ["\r", "1", "\u0080", "ö", "€", "\U0001f600", "דּ"])


class ChainTests(unittest.TestCase):
    """The real export verifies, and each of the six manipulations of CA5.2 is detected.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    def setUp(self) -> None:
        """Gives each test its own copy of the export.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        self.entries = copy.deepcopy(ENTRIES)

    def test_the_real_export_is_intact(self) -> None:
        """The gateway's export and its checkpoints verify.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        self.assertGreater(len(self.entries), 20)
        self.assertGreater(len(CHECKPOINTS), 0)
        self.assertEqual(problems(self.entries, CHECKPOINTS), [])

    def test_an_edited_entry_is_detected(self) -> None:
        """Changing one field breaks that entry's hash.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        target = next(entry for entry in self.entries if entry["type"] == "CALL_DECIDED")
        target["decision"] = "allow" if target["decision"] != "allow" else "deny"
        self.assertIn("HASH_MISMATCH", problems(self.entries))

    def test_a_deleted_entry_is_detected(self) -> None:
        """Removing an entry leaves a gap and a broken link.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        del self.entries[5]
        self.assertIn("SEQUENCE_GAP", problems(self.entries))
        self.assertIn("BROKEN_LINK", problems(self.entries))

    def test_reordered_entries_are_detected(self) -> None:
        """Swapping two entries breaks the sequence and the links.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        self.entries[3], self.entries[7] = self.entries[7], self.entries[3]
        self.assertIn("BROKEN_LINK", problems(self.entries))

    def test_an_inserted_entry_is_detected(self) -> None:
        """An extra entry, even one with a correct hash of its own, breaks the chain.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        forged = copy.deepcopy(self.entries[4])
        forged["hash"] = verifier.entry_hash(forged)
        self.entries.insert(5, forged)
        self.assertIn("SEQUENCE_GAP", problems(self.entries))

    def test_a_changed_date_is_detected(self) -> None:
        """Moving an entry back in time breaks its hash and the time order.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        self.entries[10]["ts"] = "2020-01-01T00:00:00.000000Z"
        self.assertIn("HASH_MISMATCH", problems(self.entries))
        self.assertIn("TIME_REVERSED", problems(self.entries))

    def test_a_truncated_tail_is_detected_with_exported_checkpoints(self) -> None:
        """Cutting the tail is invisible to the chain alone but not to a checkpoint kept elsewhere.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        last = int(max(checkpoint["seq"] for checkpoint in CHECKPOINTS))
        truncated = self.entries[: last - 2]
        self.assertEqual(problems(truncated), [])
        self.assertIn("TRUNCATED", problems(truncated, CHECKPOINTS))

    def test_the_command_line_reports_tampering_with_exit_code_1(self) -> None:
        """main() returns 0 for the intact files and 2 for a missing file.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        files = [
            str(HERE / "testdata" / "entries.jsonl"),
            "--checkpoints",
            str(HERE / "testdata" / "checkpoints.jsonl"),
        ]
        self.assertEqual(verifier.main(files), 0)
        self.assertEqual(verifier.main([str(HERE / "testdata" / "missing.jsonl")]), 2)


class ConstraintTests(unittest.TestCase):
    """CA5.6: under 200 lines and no import outside the standard library.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    def test_the_verifier_is_short_and_uses_only_the_standard_library(self) -> None:
        """Counts lines and checks every imported module against the standard library list.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        source = (HERE / "verify_chain.py").read_text(encoding="utf-8")
        self.assertLess(len(source.splitlines()), 200)
        modules = set()
        for node in ast.walk(ast.parse(source)):
            if isinstance(node, ast.Import):
                modules.update(alias.name.split(".")[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                modules.add(node.module.split(".")[0])
        self.assertTrue(modules)
        self.assertTrue(modules <= set(sys.stdlib_module_names) | {"__future__"}, modules)


if __name__ == "__main__":
    unittest.main()
