# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Fails when the README states a measured number that no generated report contains.

A claim is a number with a unit of measure (`ms`, `%`, `req/s`, `requests per second`, `cases`,
`tests`, `entries`), a ratio written `N of M`, or a decimal between 0 and 1 (precision, recall).
Versions, dates, ports and phase numbers are not claims. Each claim's number (for a ratio, the
whole `N of M`) must appear in one of the generated reports: attacks/REPORT.md, bench/REPORT.md,
docs/detectors.md and docs/evidence/*.md. Run from the repository root.

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path

UNIT = r"(?:ms|%|req/s|requests per second|cases|tests|entries)"
CLAIM = re.compile(
    r"(?<![\w.\-/])(?:(?P<ratio>\d+ of \d+)(?: (?:cases|tests))?"
    r"|(?P<number>\d+(?:\.\d+)?)\s?(?P<unit>" + UNIT + r")"
    r"|(?P<fraction>0\.\d+)(?![.\d]))(?![\w\-/])"
)
REPORTS = ["attacks/REPORT.md", "bench/REPORT.md", "docs/detectors.md"]


@dataclass(frozen=True)
class Claim:
    """A number the README states, and what must appear in a report for it to stand.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    text: str
    evidence: str


def claims(text: str) -> list[Claim]:
    """Finds the claims in a text.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    found: list[Claim] = []
    for match in CLAIM.finditer(text):
        if match.group("ratio"):
            found.append(Claim(match.group("ratio"), match.group("ratio")))
            if match.group(0) != match.group("ratio"):
                found.append(Claim(match.group(0), match.group("ratio")))
        elif match.group("number"):
            found.append(Claim(match.group(0), match.group("number")))
        else:
            found.append(Claim(match.group(0), match.group("fraction")))
    return found


def unbacked(readme: str, reports: str) -> list[Claim]:
    """Lists the README's claims whose evidence appears in no report.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    return [
        claim
        for claim in claims(readme)
        if not re.search(r"(?<![\d.])" + re.escape(claim.evidence) + r"(?![\d])", reports)
    ]


def main() -> int:
    """Checks README.md against the reports and prints every unbacked claim.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    root = Path(".")
    paths = [root / name for name in REPORTS] + sorted((root / "docs" / "evidence").glob("*.md"))
    reports = "\n".join(path.read_text(encoding="utf-8") for path in paths if path.exists())
    problems = unbacked((root / "README.md").read_text(encoding="utf-8"), reports)
    for claim in problems:
        print(f"README.md: '{claim.text}' is not in any generated report")
    total = len(claims((root / "README.md").read_text(encoding="utf-8")))
    print(f"check_readme_numbers: {total} claims, {len(problems)} unbacked")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
