# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Checks the Ed25519 signatures of exported checkpoints with the gateway's published key.

Usage: python verify_signatures.py checkpoints.jsonl key.json
key.json is the body of GET /.well-known/aduana-audit-key. Needs the cryptography package; the chain
itself is checked by verify_chain.py with the standard library only.

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

from __future__ import annotations

import base64
import json
import sys

from verify_chain import canonical, read_lines


def main(arguments: list[str]) -> int:
    """Verifies every checkpoint signature and prints the result.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    try:
        from cryptography.exceptions import InvalidSignature
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    except ImportError:
        print("verify_signatures: install the cryptography package", file=sys.stderr)
        return 2
    if len(arguments) != 2:
        print("usage: verify_signatures.py checkpoints.jsonl key.json", file=sys.stderr)
        return 2
    with open(arguments[1], encoding="utf-8") as handle:
        key = Ed25519PublicKey.from_public_bytes(base64.b64decode(json.load(handle)["public_key"]))
    bad = 0
    for checkpoint in read_lines(arguments[0]):
        signed = {name: checkpoint[name] for name in ("tenant_id", "seq", "hash", "ts")}
        try:
            key.verify(base64.b64decode(str(checkpoint["signature"])), canonical(signed).encode("utf-8"))
        except InvalidSignature:
            bad += 1
            print(f"{int(checkpoint['seq'])}: BAD_SIGNATURE")  # type: ignore[call-overload]
    print(f"verify_signatures: {bad} bad signatures")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
