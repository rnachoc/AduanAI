# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Tests for the commit message and commit author rules.

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_commits as checker


class MessageTests(unittest.TestCase):
    """Commit messages follow Conventional Commits and never credit a tool.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    def test_a_conventional_subject_passes(self) -> None:
        """A typed, scoped, lowercase subject without a full stop is accepted.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        self.assertEqual(checker.check_message("feat(policy): evaluate every rule and let a deny win\n"), [])

    def test_a_merge_subject_passes(self) -> None:
        """Phase merges use the merge type.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        self.assertEqual(checker.check_message("merge: phase 00, foundations\n"), [])

    def test_a_co_author_trailer_is_rejected(self) -> None:
        """Co-author trailers are refused whatever their case.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        message = "fix: keep the log closed\n\nco-authored-by: Someone <noreply@anthropic.com>\n"
        self.assertTrue(checker.check_message(message))

    def test_a_generated_with_line_is_rejected(self) -> None:
        """Tool attribution lines are refused.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        self.assertTrue(checker.check_message("docs: say more\n\nGenerated with a tool\n"))

    def test_bad_subjects_are_rejected(self) -> None:
        """Untyped, capitalised, long, full-stopped or vague subjects are refused.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        for subject in (
            "update readme",
            "feat: Add things",
            "feat: add things.",
            "feat: " + "a" * 70,
            "chore: wip",
            "fix: fix stuff",
            "chore: update",
            "feat: add rocket \U0001f680",
        ):
            with self.subTest(subject=subject):
                self.assertTrue(checker.check_message(subject + "\n"))

    def test_git_comment_lines_are_ignored(self) -> None:
        """Lines git adds as comments are not part of the message.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        self.assertEqual(checker.check_message("test: add a case\n# Please enter the commit message\n"), [])


class AuthorTests(unittest.TestCase):
    """Every commit on a branch is authored and committed by the owner.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    def test_the_owner_identity_passes(self) -> None:
        """The exact name and address are accepted.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        self.assertEqual(checker.check_identity("Ignacio Rodríguez Caro", "rnachoccontact@gmail.com"), [])

    def test_the_placeholder_global_identity_is_rejected(self) -> None:
        """The example address in the machine's global git configuration is refused.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        problems = checker.check_identity("tuusuario", "12345678+tuusuario@users.noreply.github.com")
        self.assertTrue(problems)


if __name__ == "__main__":
    unittest.main()
