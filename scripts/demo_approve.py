# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Approves (or rejects) the pending approvals of one tool, as a demo approver would in the console.

Usage: python scripts/demo_approve.py <gateway> <approver e-mail> <tool> [approve|reject]

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

from __future__ import annotations

import os
import sys

from demo_seed import Api


def main(arguments: list[str]) -> int:
    """Signs in as the approver and decides every pending approval of the tool.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    if len(arguments) < 3:
        print("usage: demo_approve.py <gateway> <approver e-mail> <tool> [approve|reject]", file=sys.stderr)
        return 2
    gateway, email, tool = arguments[:3]
    action = arguments[3] if len(arguments) > 3 else "approve"
    password = os.environ.get("ADUANA_ADMIN_PASSWORD", "local-only")
    api = Api(gateway)
    api.call("POST", "/api/v1/auth/login", {"email": email, "password": password})
    api.call("GET", "/api/v1/auth/me")
    approvals = api.call("GET", "/api/v1/approvals?state=pending")
    pending = [a for a in approvals if a["tool"] == tool]  # type: ignore[union-attr, index]
    for approval in pending:
        api.call("POST", f"/api/v1/approvals/{approval['id']}/{action}")
    print(f"demo_approve: {action}d {len(pending)} approvals of {tool}")
    return 0 if pending else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
