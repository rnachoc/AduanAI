# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Fails the build when a shipped dependency has no permissive licence (no GPL, AGPL or SSPL).

Maven dependencies come from `mvn dependency:list` output and their POMs in the local
repository, parents included. npm packages come from package-lock.json files. A dependency
passes when at least one of its licences is on the allow list; a dependency whose licence
cannot be found passes only if build-config/license-exceptions.txt names it with a reason.

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

ALLOWED = re.compile(
    r"apache|\bmit\b|\bbsd\b|bsd-[23]|eclipse public|\bepl\b|epl-?[12]|mozilla public|\bmpl\b|mpl-2"
    r"|\bisc\b|cc0|public domain|unlicense|universal permissive|\bupl\b|eclipse distribution|\bedl\b"
    r"|0bsd|blueoak|python-2|psf|zlib|bouncy castle",
    re.IGNORECASE,
)
BLOCKED = re.compile(r"\bgpl|general public|sspl|server side public|business source|commons clause", re.IGNORECASE)
DEPENDENCY_LINE = re.compile(r"^\s*([\w.\-]+):([\w.\-]+):[\w\-]+(?::[\w.\-]+)?:([\w.\-]+):(compile|runtime|provided|test|system)")


def is_allowed(names: list[str]) -> bool:
    """A dependency is allowed when any one of its licences is permissive and not copyleft.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    return any(ALLOWED.search(name) and not BLOCKED.search(name) for name in names)


def strip_namespace(element: ElementTree.Element) -> None:
    """Drops XML namespaces so POM tags can be found by their plain names.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    for node in element.iter():
        if isinstance(node.tag, str) and "}" in node.tag:
            node.tag = node.tag.split("}", 1)[1]


def pom_licences(repository: Path, group: str, artifact: str, version: str, depth: int = 0) -> list[str]:
    """Reads the licence names of a POM, following parents until one declares licences.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    pom = repository / group.replace(".", "/") / artifact / version / f"{artifact}-{version}.pom"
    if depth > 10 or not pom.is_file():
        return []
    root = ElementTree.parse(pom).getroot()
    strip_namespace(root)
    names = [
        (license_node.findtext("name") or license_node.findtext("url") or "").strip()
        for license_node in root.findall("./licenses/license")
    ]
    names = [name for name in names if name]
    if names:
        return names
    parent = root.find("./parent")
    if parent is None:
        return []
    return pom_licences(
        repository,
        parent.findtext("groupId") or "",
        parent.findtext("artifactId") or "",
        parent.findtext("version") or "",
        depth + 1,
    )


def check_maven(lines: list[str], repository: Path, exceptions: dict[str, str]) -> list[str]:
    """Checks every non-test dependency listed by `mvn dependency:list`.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    problems = []
    seen = set()
    for line in lines:
        match = DEPENDENCY_LINE.match(line)
        if not match or match.group(4) == "test":
            continue
        group, artifact, version = match.group(1), match.group(2), match.group(3)
        key = f"{group}:{artifact}"
        if key in seen or group.startswith("io.github.rnachoc.aduana"):
            continue
        seen.add(key)
        names = pom_licences(repository, group, artifact, version)
        if is_allowed(names):
            continue
        if not names and key in exceptions:
            continue
        found = ", ".join(names) or "no licence found"
        problems.append(f"{key}:{version}: {found}")
    return problems


def check_npm_lock(text: str, path: str, exceptions: dict[str, str]) -> list[str]:
    """Checks every non-development package in an npm lock file.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    problems = []
    for location, package in json.loads(text).get("packages", {}).items():
        if not location or package.get("dev") or package.get("link"):
            continue
        name = location.split("node_modules/")[-1]
        expression = package.get("license") or ""
        options = [part.strip("() ") for part in re.split(r"\s+OR\s+", expression)]
        if expression and is_allowed(options):
            continue
        if not expression and name in exceptions:
            continue
        problems.append(f"{path}: {name}@{package.get('version')}: {expression or 'no licence found'}")
    return problems


def read_exceptions(path: Path) -> dict[str, str]:
    """Reads `coordinates  reason` lines; blank lines and comments are ignored.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    if not path.is_file():
        return {}
    entries = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#"):
            key, _, reason = line.partition(" ")
            entries[key] = reason.strip()
    return entries


def main(arguments: list[str]) -> int:
    """Usage: check_licenses.py <maven-repository> <dependency-list-files...> [--npm <lock files...>].

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    if not arguments:
        print("usage: check_licenses.py <maven-repository> <dependency lists...> [--npm <lock files...>]")
        return 2
    exceptions = read_exceptions(Path("build-config/license-exceptions.txt"))
    maven_files, npm_files = arguments[1:], []
    if "--npm" in maven_files:
        index = maven_files.index("--npm")
        maven_files, npm_files = maven_files[:index], maven_files[index + 1 :]
    lines = [line for name in maven_files for line in Path(name).read_text(encoding="utf-8").splitlines()]
    problems = check_maven(lines, Path(arguments[0]), exceptions)
    for name in npm_files:
        problems += check_npm_lock(Path(name).read_text(encoding="utf-8"), name, exceptions)
    for problem in problems:
        print(f"licence not allowed: {problem}")
    print(f"check_licenses: {len(lines)} Maven lines, {len(npm_files)} npm lock files, {len(problems)} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
