# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Fails when a Dockerfile, compose file or workflow uses an image that is not pinned by digest.

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

from __future__ import annotations

import re
import subprocess
import sys

REFERENCE = re.compile(r"^\s*(?:FROM|image:|-?\s*image:)\s+(\S+)", re.IGNORECASE)
LOCAL_IMAGES = re.compile(r"^aduana-[\w-]+:local$")


def unpinned(path: str, text: str) -> list[str]:
    """Lists image references in a file that carry no @sha256 digest.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    problems = []
    for number, line in enumerate(text.splitlines(), start=1):
        match = REFERENCE.match(line)
        if not match:
            continue
        image = match.group(1)
        if "@sha256:" in image or LOCAL_IMAGES.match(image) or image.startswith("$") or image.lower() == "scratch":
            continue
        if image.isidentifier() or re.fullmatch(r"[a-z][\w-]*", image):
            continue
        problems.append(f"{path}:{number}: {image} is not pinned by digest")
    return problems


def main() -> int:
    """Checks every tracked Dockerfile, compose file and GitHub workflow.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    files = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.splitlines()
    wanted = [f for f in files if f.endswith(("Dockerfile", "compose.yaml", ".yml")) or "compose" in f]
    problems = [p for f in wanted for p in unpinned(f, open(f, encoding="utf-8").read())]
    for problem in problems:
        print(problem)
    print(f"check_pinned_images: {len(wanted)} files, {len(problems)} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
