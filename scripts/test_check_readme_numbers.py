# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Tests for the check that every measured number in the README comes from a generated report.

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_readme_numbers as checker

REPORTS = """
| 200 | 8.1 | 11.9 | 14.2 |
Deterministic defences: 78 of 78 cases stopped.
| `phone_es` | 10 | 10 | 10 | 1 | 0.91 | 1.00 |
"""


class ClaimTests(unittest.TestCase):
    """Which numbers count as claims.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    def test_units_and_ratios_are_claims(self) -> None:
        """Milliseconds, percentages, rates, case counts and `N of M` are claims.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        text = "adds 14.2 ms at 200 req/s; 78 of 78 cases stopped; precision 0.91 and 95 % coverage"
        self.assertEqual(
            [claim.text for claim in checker.claims(text)],
            ["14.2 ms", "200 req/s", "78 of 78", "78 of 78 cases", "0.91", "95 %"],
        )

    def test_versions_dates_and_ports_are_not_claims(self) -> None:
        """Versions, dates, ports and phase numbers are not measurements.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        text = "Java 25, MCP 2026-07-28, port 8780, Phase 11, version 0.1.0"
        self.assertEqual(checker.claims(text), [])


class CheckTests(unittest.TestCase):
    """The check against the reports.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    def test_numbers_found_in_a_report_pass(self) -> None:
        """A README that quotes the reports passes.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        readme = "Aduana adds 14.2 ms at p99 at 200 req/s. 78 of 78 deterministic cases stopped."
        self.assertEqual(checker.unbacked(readme, REPORTS), [])

    def test_a_number_edited_by_hand_fails(self) -> None:
        """Changing 14.2 to 9.9 by hand is caught.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        readme = "Aduana adds 9.9 ms at p99 at 200 req/s."
        self.assertEqual([claim.text for claim in checker.unbacked(readme, REPORTS)], ["9.9 ms"])

    def test_a_ratio_must_match_as_a_whole(self) -> None:
        """`80 of 78` fails even though both numbers appear somewhere.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        readme = "80 of 78 cases stopped."
        self.assertEqual([claim.text for claim in checker.unbacked(readme, REPORTS)], ["80 of 78", "80 of 78 cases"])


if __name__ == "__main__":
    unittest.main()
