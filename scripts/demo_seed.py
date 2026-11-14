# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Prepares a running stack for the demo through the administration API and prints an agent key.

It signs in as the bootstrap administrator, marks the demo tools (side effects, untrusted source,
cost), creates the `support-agent` principal if it does not exist and issues it a fresh key. Only
the key goes to standard output, so `KEY=$(python scripts/demo_seed.py ...)` works.

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

from __future__ import annotations

import http.cookiejar
import json
import os
import sys
import time
import urllib.error
import urllib.request

TOOL_METADATA: dict[str, dict[str, object]] = {
    "crm/update_customer_email": {"sideEffect": True, "sensitivity": "high"},
    "crm/get_customer": {"sensitivity": "medium"},
    "payments/refund": {"sideEffect": True, "sensitivity": "high", "cost": 10},
    "webfetch/fetch_url": {"untrustedSource": True},
}


class Api:
    """A tiny client for the administration API with a cookie jar and the CSRF header.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    def __init__(self, base: str) -> None:
        """Targets a gateway.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        self.base = base.rstrip("/")
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))

    def call(self, method: str, path: str, body: object | None = None) -> object:
        """Sends one request and returns the parsed JSON body (None when empty).

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Content-Type", "application/json")
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                request.add_header("X-XSRF-TOKEN", cookie.value)
        with self.opener.open(request, timeout=30) as response:
            text = response.read()
        return json.loads(text) if text else None


def wait_for_tools(api: Api, expected: int) -> list[dict[str, object]]:
    """Waits until the catalog lists at least the expected number of tools.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    for _ in range(60):
        tools = api.call("GET", "/api/v1/tools")
        if isinstance(tools, list) and len(tools) >= expected:
            return tools
        time.sleep(1)
    raise SystemExit("demo_seed: the catalog never listed the demo tools")


def main(arguments: list[str]) -> int:
    """Seeds the stack whose gateway URL is the only argument and prints the agent key.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    api = Api(arguments[0] if arguments else "http://gateway:8080")
    email = os.environ.get("ADUANA_ADMIN_EMAIL", "admin@example.com")
    password = os.environ.get("ADUANA_ADMIN_PASSWORD", "local-only")
    api.call("POST", "/api/v1/auth/login", {"email": email, "password": password})
    api.call("GET", "/api/v1/auth/me")
    for tool in wait_for_tools(api, 7):
        change = TOOL_METADATA.get(f"{tool['server']}/{tool['name']}")
        if change:
            api.call("PATCH", f"/api/v1/tools/{tool['id']}", change)
    principals = api.call("GET", "/api/v1/principals")
    agent = next((p for p in principals if p["name"] == "support-agent"), None)  # type: ignore[union-attr]
    if agent is None:
        body = {"kind": "agent", "name": "support-agent", "attributes": {"team": "support", "env": "demo"}}
        agent = api.call("POST", "/api/v1/principals", body)
    key = api.call("POST", f"/api/v1/principals/{agent['id']}/keys", {})  # type: ignore[index]
    print(key["secret"])  # type: ignore[index]
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except urllib.error.HTTPError as error:
        print(f"demo_seed: {error.code} {error.reason} on {error.url}", file=sys.stderr)
        sys.exit(1)
