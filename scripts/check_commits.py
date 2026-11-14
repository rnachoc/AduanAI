# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Enforces the commit rules: Conventional Commits subjects, the owner as sole author, no tool credit.

Used by the commit-msg hook on one message and by `make lint` on every commit of the branch.

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

OWNER_NAME = "Ignacio Rodríguez Caro"
OWNER_EMAIL = "rnachoccontact@gmail.com"
TYPES = "feat|fix|test|docs|refactor|perf|build|ci|chore|style|merge"
SCOPES = "policy|audit|gateway|cli|console|demo|attacks|bench|verifier|deploy"
SUBJECT = re.compile(rf"^(?:{TYPES})(?:\((?:{SCOPES})\))?: [a-z0-9`].*[^.\s]$")
FORBIDDEN = ("co-authored-by", "generated with", "noreply@anthropic.com")
VAGUE = re.compile(r"\b(wip|fix stuff)\b|^\w+(?:\([a-z]+\))?: (update|final)$", re.IGNORECASE)
EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿️]")
MAX_SUBJECT = 72


def check_message(message: str) -> list[str]:
    """Returns every rule a commit message breaks; empty when it is acceptable.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    lines = [line for line in message.splitlines() if not line.startswith("#")]
    while lines and not lines[-1].strip():
        lines.pop()
    if not lines or not lines[0].strip():
        return ["the message is empty"]
    subject = lines[0]
    problems = []
    if len(subject) > MAX_SUBJECT:
        problems.append(f"the subject is longer than {MAX_SUBJECT} characters")
    if not SUBJECT.match(subject):
        problems.append("the subject is not '<type>(<scope>): <lowercase summary>' without a full stop")
    if VAGUE.search(subject):
        problems.append("the subject is vague (WIP, fix stuff, update, final)")
    if EMOJI.search(message):
        problems.append("the message contains an emoji")
    lowered = message.lower()
    problems += [f"the message contains '{word}'" for word in FORBIDDEN if word in lowered]
    return problems


def check_identity(name: str, email: str) -> list[str]:
    """Returns a problem unless the identity is exactly the owner's.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    if name == OWNER_NAME and email == OWNER_EMAIL:
        return []
    return [f"'{name} <{email}>' is not '{OWNER_NAME} <{OWNER_EMAIL}>'"]


def check_range(revisions: str) -> list[str]:
    """Checks the message, author and committer of every commit in a git revision range.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    separator = "\x1e"
    output = subprocess.run(
        ["git", "log", f"--format=%h%x1f%an%x1f%ae%x1f%cn%x1f%ce%x1f%B{separator}", revisions],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    ).stdout
    problems = []
    for record in filter(str.strip, output.split(separator)):
        short, author, author_email, committer, committer_email, body = record.strip("\n").split("\x1f", 5)
        found = check_message(body) + check_identity(author, author_email)
        found += check_identity(committer, committer_email)
        problems += [f"{short}: {problem}" for problem in found]
    return problems


def main(arguments: list[str]) -> int:
    """Checks a message file (`check_commits.py <file>`) or a range (`--range <revs>`).

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    if len(arguments) == 2 and arguments[0] == "--range":
        problems = check_range(arguments[1])
    elif len(arguments) == 1:
        problems = check_message(Path(arguments[0]).read_text(encoding="utf-8"))
    else:
        print("usage: check_commits.py <message-file> | --range <revisions>")
        return 2
    for problem in problems:
        print(problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
