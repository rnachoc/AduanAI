# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Tests for the authorship checker that keeps every type and function credited.

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_authorship as checker  # noqa: E402

AUTHOR = "Ignacio Rodríguez Caro <rnachoccontact@gmail.com>"
JAVA_HEADER = "// SPDX-License-Identifier: Apache-2.0\n// Copyright 2026 Ignacio Rodríguez Caro\n"
PY_HEADER = "# SPDX-License-Identifier: Apache-2.0\n# Copyright 2026 Ignacio Rodríguez Caro\n"


def doc(text: str = "Explains why.") -> str:
    """Builds a Javadoc block that names the author.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    return f"/**\n * {text}\n *\n * @author {AUTHOR}\n */\n"


GOOD_JAVA = (
    JAVA_HEADER
    + "package a;\n\nimport java.util.List;\n\n"
    + doc()
    + "public final class Good {\n"
    + "  private final List<String> names = List.of(\"a\", \"b\");\n"
    + "  private final Runnable task = new Runnable() {\n"
    + "    @Override public void run() { }\n"
    + "  };\n\n"
    + doc()
    + "  public Good() { }\n\n"
    + doc()
    + "  private static <T> T first(List<T> items) {\n"
    + "    String text = \"class Fake { void x() {} }\";\n"
    + "    return items.get(0);\n"
    + "  }\n\n"
    + doc()
    + "  enum Color { RED(\"r\"), GREEN(\"g\");\n"
    + "    private final String code;\n"
    + doc()
    + "    Color(String code) { this.code = code; }\n"
    + "  }\n\n"
    + doc()
    + "  record Point(int x, int y) {\n"
    + "    public int x() { return x; }\n"
    + doc()
    + "    public Point {\n"
    + "      if (x < 0) { throw new IllegalArgumentException(\"x\"); }\n"
    + "    }\n"
    + "  }\n\n"
    + doc()
    + "  interface Shape {\n"
    + doc()
    + "    double area();\n"
    + "  }\n"
    + "}\n"
)


class JavaTests(unittest.TestCase):
    """Java declarations that must and must not carry an author.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    def test_a_fully_documented_file_passes(self) -> None:
        """A file where every type and method names the author has no problems.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        self.assertEqual(checker.check_java(GOOD_JAVA, "Good.java"), [])

    def test_removing_the_author_from_a_class_fails(self) -> None:
        """A class whose Javadoc lost its author line is reported.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        source = GOOD_JAVA.replace(doc() + "public final class Good", "/** Good. */\npublic final class Good")
        problems = checker.check_java(source, "Good.java")
        self.assertEqual(len(problems), 1)
        self.assertIn("class Good", problems[0])

    def test_a_private_method_without_javadoc_fails(self) -> None:
        """Private methods are not exempt.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        source = GOOD_JAVA.replace(doc() + "  private static <T> T first", "  private static <T> T first")
        problems = checker.check_java(source, "Good.java")
        self.assertEqual(len(problems), 1)
        self.assertIn("first", problems[0])

    def test_an_interface_method_without_javadoc_fails(self) -> None:
        """Abstract methods without a body still need the author.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        source = GOOD_JAVA.replace(doc() + "    double area();", "    double area();")
        self.assertEqual(len(checker.check_java(source, "Good.java")), 1)

    def test_a_compact_record_constructor_without_javadoc_fails(self) -> None:
        """Compact constructors are methods too.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        source = GOOD_JAVA.replace(doc() + "    public Point {", "    public Point {")
        self.assertEqual(len(checker.check_java(source, "Good.java")), 1)

    def test_test_methods_are_exempt_but_test_classes_are_not(self) -> None:
        """A @Test method needs no Javadoc; its class does.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        body = "  @Test\n  void works() { }\n}\n"
        documented = JAVA_HEADER + doc() + "class SomeTest {\n" + body
        undocumented = JAVA_HEADER + "class SomeTest {\n" + body
        self.assertEqual(checker.check_java(documented, "SomeTest.java"), [])
        self.assertEqual(len(checker.check_java(undocumented, "SomeTest.java")), 1)

    def test_a_missing_licence_header_fails(self) -> None:
        """Every source file starts with the SPDX and copyright lines.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        problems = checker.check_java(GOOD_JAVA.replace(JAVA_HEADER, ""), "Good.java")
        self.assertTrue(any("header" in p for p in problems))


class PythonTests(unittest.TestCase):
    """Python modules, classes and functions must end their docstring with the author.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    GOOD = (
        PY_HEADER
        + '"""Module.\n\nAuthor: ' + AUTHOR + '\n"""\n\n\n'
        + 'def run() -> None:\n    """Runs.\n\n    Author: ' + AUTHOR + '\n    """\n'
    )

    def test_a_documented_module_passes(self) -> None:
        """A module whose docstrings all end with the author passes.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        self.assertEqual(checker.check_python(self.GOOD, "m.py"), [])

    def test_a_function_without_the_author_fails(self) -> None:
        """A docstring that does not end with the author line is reported.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        source = self.GOOD.replace('    """Runs.\n\n    Author: ' + AUTHOR + '\n    """\n', '    """Runs."""\n')
        problems = checker.check_python(source, "m.py")
        self.assertEqual(len(problems), 1)
        self.assertIn("run", problems[0])


class TypeScriptTests(unittest.TestCase):
    """TypeScript classes, exported functions and methods need TSDoc with the author.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    GOOD = (
        JAVA_HEADER
        + "import { Component } from '@angular/core';\n\n"
        + doc()
        + "@Component({ selector: 'x', template: `<p>{{ a }}</p>` })\n"
        + "export class Thing {\n"
        + "  private readonly handler = (): void => { };\n"
        + doc()
        + "  constructor(private readonly name: string) { }\n"
        + doc()
        + "  get label(): string { return `${this.name}`; }\n"
        + "}\n\n"
        + doc()
        + "export function helper(value: number): number {\n  return value + 1;\n}\n\n"
        + "function internal(): void { }\n\n"
        + "export interface Shape {\n  area(): number;\n}\n"
    )

    def test_a_documented_file_passes(self) -> None:
        """Classes, constructors, accessors and exported functions with TSDoc pass.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        self.assertEqual(checker.check_typescript(self.GOOD, "thing.ts"), [])

    def test_an_exported_function_without_tsdoc_fails(self) -> None:
        """Exported functions are part of the rule.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        source = self.GOOD.replace(doc() + "export function helper", "export function helper")
        problems = checker.check_typescript(source, "thing.ts")
        self.assertEqual(len(problems), 1)
        self.assertIn("helper", problems[0])

    def test_a_class_without_tsdoc_fails(self) -> None:
        """Removing the author from a class is reported.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        source = self.GOOD.replace(doc() + "@Component", "@Component")
        problems = checker.check_typescript(source, "thing.ts")
        self.assertEqual(len(problems), 1)
        self.assertIn("Thing", problems[0])


if __name__ == "__main__":
    unittest.main()
