# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Probes a running stack and exits non-zero on the first check that fails.

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

from __future__ import annotations

import json
import sys
import urllib.request


def get_json(url: str) -> dict[str, object]:
    """Fetches a URL and parses its JSON body.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    with urllib.request.urlopen(url, timeout=5) as response:
        return json.loads(response.read())


def main(arguments: list[str]) -> int:
    """Runs every smoke check against the gateway base URL given as the only argument.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    base = arguments[0] if arguments else "http://gateway:8080"
    health = get_json(base + "/healthz")
    if health.get("status") != "ok":
        print(f"smoke: /healthz answered {health}")
        return 1
    print("smoke: gateway healthy")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
