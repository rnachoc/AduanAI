# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Tests for the licence gate over Maven and npm dependencies.

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_licenses as checker  # noqa: E402


def write_pom(repository: Path, coordinates: str, body: str) -> None:
    """Writes a minimal POM into a fake local Maven repository.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    group, artifact, version = coordinates.split(":")
    folder = repository / group.replace(".", "/") / artifact / version
    folder.mkdir(parents=True, exist_ok=True)
    pom = f"<project><groupId>{group}</groupId><artifactId>{artifact}</artifactId><version>{version}</version>{body}</project>"
    (folder / f"{artifact}-{version}.pom").write_text(pom, encoding="utf-8")


def licences(*names: str) -> str:
    """Builds a POM licences block.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    return "<licenses>" + "".join(f"<license><name>{n}</name></license>" for n in names) + "</licenses>"


class MavenTests(unittest.TestCase):
    """Maven dependencies pass only with at least one permissive licence.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    def setUp(self) -> None:
        """Creates an empty fake repository for each test.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        self.folder = tempfile.TemporaryDirectory()
        self.repository = Path(self.folder.name)

    def tearDown(self) -> None:
        """Removes the fake repository.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        self.folder.cleanup()

    def test_an_apache_dependency_passes(self) -> None:
        """Apache-2.0 is allowed.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        write_pom(self.repository, "a:b:1", licences("The Apache Software License, Version 2.0"))
        self.assertEqual(checker.check_maven(["a:b:jar:1:compile"], self.repository, {}), [])

    def test_an_agpl_dependency_fails(self) -> None:
        """AGPL is refused.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        write_pom(self.repository, "a:b:1", licences("GNU Affero General Public License v3"))
        problems = checker.check_maven(["a:b:jar:1:compile"], self.repository, {})
        self.assertEqual(len(problems), 1)
        self.assertIn("Affero", problems[0])

    def test_a_dual_licence_with_a_permissive_option_passes(self) -> None:
        """EPL-2.0 or GPL-2.0 with the classpath exception can be taken as EPL-2.0.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        write_pom(self.repository, "a:b:1", licences("EPL 2.0", "GPL2 w/ CPE"))
        self.assertEqual(checker.check_maven(["a:b:jar:1:compile"], self.repository, {}), [])

    def test_the_licence_is_inherited_from_the_parent(self) -> None:
        """A POM without licences inherits them from its parent.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        write_pom(self.repository, "a:parent:1", licences("MIT License"))
        parent = "<parent><groupId>a</groupId><artifactId>parent</artifactId><version>1</version></parent>"
        write_pom(self.repository, "a:b:1", parent)
        self.assertEqual(checker.check_maven(["a:b:jar:1:compile"], self.repository, {}), [])

    def test_an_unknown_licence_fails_unless_it_is_an_explained_exception(self) -> None:
        """A dependency with no licence anywhere is refused unless listed with a reason.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        write_pom(self.repository, "a:b:1", "")
        self.assertEqual(len(checker.check_maven(["a:b:jar:1:compile"], self.repository, {})), 1)
        exceptions = {"a:b": "MIT per the project README"}
        self.assertEqual(checker.check_maven(["a:b:jar:1:compile"], self.repository, exceptions), [])


class NpmTests(unittest.TestCase):
    """npm packages pass only with a permissive SPDX expression.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    def test_package_lock_licences_are_checked(self) -> None:
        """A GPL package in a lock file is refused and an MIT one is not.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        lock = {
            "packages": {
                "": {"name": "root"},
                "node_modules/ok": {"version": "1.0.0", "license": "MIT"},
                "node_modules/either": {"version": "1.0.0", "license": "(MIT OR GPL-3.0)"},
                "node_modules/bad": {"version": "1.0.0", "license": "GPL-3.0-only"},
            }
        }
        problems = checker.check_npm_lock(json.dumps(lock), "package-lock.json", {})
        self.assertEqual(len(problems), 1)
        self.assertIn("bad", problems[0])


if __name__ == "__main__":
    unittest.main()
