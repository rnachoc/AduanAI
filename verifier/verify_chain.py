# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Checks an Aduana audit export without any Aduana code, using only the Python standard library.

Usage: python verify_chain.py entries.jsonl [--checkpoints checkpoints.jsonl]
Exit codes: 0 intact, 1 tampered, 2 usage error. The format is in docs/audit-format.md.
Checkpoint signatures are checked by verify_signatures.py, which needs the cryptography package.

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys

GENESIS = "0" * 64


def number(value: float) -> str:
    """Formats a number as ECMAScript does (RFC 8785 section 3.2.2.3).

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    if value != value or value in (float("inf"), float("-inf")):
        raise ValueError("NaN and infinities have no JSON form")
    if value == 0:
        return "0"
    mantissa, _, exponent = repr(abs(value)).partition("e")
    whole, _, fraction = mantissa.partition(".")
    raw = whole + fraction
    stripped = raw.lstrip("0")
    point = len(whole) + (int(exponent) if exponent else 0) - (len(raw) - len(stripped))
    digits = stripped.rstrip("0")
    k, sign = len(digits), "-" if value < 0 else ""
    if k <= point <= 21:
        return sign + digits + "0" * (point - k)
    if 0 < point <= 21:
        return sign + digits[:point] + "." + digits[point:]
    if -6 < point <= 0:
        return sign + "0." + "0" * -point + digits
    shown = digits if k == 1 else digits[0] + "." + digits[1:]
    return f"{sign}{shown}e{'-' if point - 1 < 0 else '+'}{abs(point - 1)}"


def canonical(value: object) -> str:
    """Returns the RFC 8785 canonical form of a parsed JSON value.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    if isinstance(value, dict):
        keys = sorted(value, key=lambda key: key.encode("utf-16-be", "surrogatepass"))
        return "{" + ",".join(canonical(key) + ":" + canonical(value[key]) for key in keys) + "}"
    if isinstance(value, list):
        return "[" + ",".join(canonical(item) for item in value) + "]"
    if isinstance(value, str):
        text = json.dumps(value, ensure_ascii=False)
        text.encode("utf-8")
        return text
    if value is True:
        return "true"
    if value is False:
        return "false"
    if value is None:
        return "null"
    return number(float(value))  # type: ignore[arg-type]


def entry_hash(entry: dict[str, object]) -> str:
    """Computes SHA-256(prev_hash bytes || canonical entry without "hash"), as lowercase hex.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    body = {key: value for key, value in entry.items() if key != "hash"}
    previous = bytes.fromhex(str(entry["prev_hash"]))
    if len(previous) != 32:
        raise ValueError("prev_hash must be 32 bytes")
    return hashlib.sha256(previous + canonical(body).encode("utf-8")).hexdigest()


def verify(entries: list[dict[str, object]], checkpoints: list[dict[str, object]]) -> list[str]:
    """Lists every problem: gaps, broken links, hash mismatches, reversed times, truncation.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    problems: list[str] = []
    hashes: dict[int, object] = {}
    previous_hash, previous_ts, expected = GENESIS, "", 1
    for entry in entries:
        seq = int(entry.get("seq", -1))  # type: ignore[call-overload]
        if seq != expected:
            problems.append(f"{seq}: SEQUENCE_GAP expected {expected}")
        if entry.get("prev_hash") != previous_hash:
            problems.append(f"{seq}: BROKEN_LINK")
        try:
            if entry_hash(entry) != entry.get("hash"):
                problems.append(f"{seq}: HASH_MISMATCH")
        except (ValueError, KeyError, UnicodeEncodeError) as error:
            problems.append(f"{seq}: MALFORMED {error}")
        if str(entry.get("ts", "")) < previous_ts:
            problems.append(f"{seq}: TIME_REVERSED")
        hashes[seq] = entry.get("hash")
        previous_hash, previous_ts, expected = str(entry.get("hash")), str(entry.get("ts", "")), seq + 1
    last = expected - 1
    for checkpoint in checkpoints:
        seq = int(checkpoint["seq"])  # type: ignore[call-overload]
        if seq > last:
            problems.append(f"{seq}: TRUNCATED the log ends at {last}")
        elif hashes.get(seq) != checkpoint.get("hash"):
            problems.append(f"{seq}: CHECKPOINT_MISMATCH")
    return problems


def read_lines(path: str) -> list[dict[str, object]]:
    """Reads a JSON Lines file; integers are read as floats, as RFC 8785 requires.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    with open(path, encoding="utf-8") as handle:
        return [json.loads(line, parse_int=float) for line in handle if line.strip()]


def main(arguments: list[str]) -> int:
    """Verifies the files named on the command line and prints the result.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    parser = argparse.ArgumentParser(description="Verify an Aduana audit export.")
    parser.add_argument("entries")
    parser.add_argument("--checkpoints")
    try:
        options = parser.parse_args(arguments)
        entries = read_lines(options.entries)
        checkpoints = read_lines(options.checkpoints) if options.checkpoints else []
    except (OSError, ValueError) as error:
        print(f"verify_chain: {error}", file=sys.stderr)
        return 2
    except SystemExit:
        return 2
    problems = verify(entries, checkpoints)
    for problem in problems:
        print(problem)
    status = "intact" if not problems else f"{len(problems)} problems"
    print(f"verify_chain: {len(entries)} entries, {len(checkpoints)} checkpoints, {status}")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
