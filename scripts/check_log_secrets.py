# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Fails if an audit export holds a sensitive value in clear (CA7.3).

It looks for whole e-mail addresses, IBANs that pass mod 97, card numbers that pass Luhn and DNIs with
a valid letter. Redacted forms such as [REDACTED:iban:XX20…6001] do not match.

Usage: python scripts/check_log_secrets.py entries.jsonl

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

from __future__ import annotations

import re
import sys

EMAIL = re.compile(r"(?<![\w.+*-])[\w.+-]+@[\w-]+(?:\.[\w-]+)+")
IBAN = re.compile(r"\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b")
CARD = re.compile(r"\b(?:\d[ -]?){12,18}\d\b")
DNI = re.compile(r"\b\d{8}[A-Z]\b")


def valid_iban(value: str) -> bool:
    """Checks ISO 13616 mod 97.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    rearranged = value[4:] + value[:4]
    return int("".join(str(int(c, 36)) for c in rearranged)) % 97 == 1


def valid_card(value: str) -> bool:
    """Checks the Luhn digit.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    digits = [int(c) for c in value if c.isdigit()]
    if not any(digits):
        return False
    total = sum(d if i % 2 == 0 else (d * 2 - 9 if d * 2 > 9 else d * 2) for i, d in enumerate(reversed(digits)))
    return 13 <= len(digits) <= 19 and total % 10 == 0


def valid_dni(value: str) -> bool:
    """Checks the DNI control letter.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    return "TRWAGMYFPDXBNJZSQVHLCKE"[int(value[:8]) % 23] == value[8]


def findings(text: str) -> list[str]:
    """Lists every sensitive value found in clear.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    found = [f"email {m}" for m in EMAIL.findall(text)]
    found += [f"iban {m}" for m in IBAN.findall(text) if valid_iban(m)]
    found += [f"card {m}" for m in CARD.findall(text) if valid_card(m)]
    found += [f"dni {m}" for m in DNI.findall(text) if valid_dni(m)]
    return found


def main(arguments: list[str]) -> int:
    """Scans the export named on the command line.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    if len(arguments) != 1:
        print("usage: check_log_secrets.py entries.jsonl", file=sys.stderr)
        return 2
    with open(arguments[0], encoding="utf-8") as handle:
        lines = handle.readlines()
    problems = [f"line {number}: {finding}" for number, line in enumerate(lines, 1) for finding in findings(line)]
    for problem in problems:
        print(problem)
    print(f"check_log_secrets: {len(lines)} entries, {len(problems)} values in clear")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
