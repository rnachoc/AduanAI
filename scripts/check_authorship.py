# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Ignacio Rodríguez Caro
"""Fails the build when a source file, type or function does not name its author.

Java types and methods need Javadoc with an @author tag, TypeScript classes, methods
and exported functions need TSDoc with an @author tag, and Python modules, classes and
functions need a docstring whose last line is the author. Every source file also needs
the SPDX licence header.

Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
"""

from __future__ import annotations

import ast
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

AUTHOR = "Ignacio Rodríguez Caro <rnachoccontact@gmail.com>"
AUTHOR_TAG = "@author " + AUTHOR
PYTHON_AUTHOR_LINE = "Author: " + AUTHOR
SPDX = "SPDX-License-Identifier: Apache-2.0"
COPYRIGHT = "Copyright 2026 Ignacio Rodríguez Caro"
EXCLUDED_PARTS = {"node_modules", "target", "dist", "build", ".angular", "generated"}
TEST_ANNOTATIONS = re.compile(r"@(Test|ParameterizedTest|RepeatedTest|TestFactory|Property|Example)\b")
ANNOTATION = re.compile(r"@\w+(?:\.\w+)*(?:\s*\([^()]*(?:\([^()]*\)[^()]*)*\))?")
JAVA_TYPE = re.compile(r"(?<![.\w])(class|interface|enum|record)\s+(\w+)")
TS_CLASS = re.compile(r"(?<![.\w])class\s+(\w+)")
TS_FUNCTION = re.compile(r"^export\s+(?:default\s+)?(?:async\s+)?function\s*\*?\s*(\w+)")
TS_INTERFACE = re.compile(r"(?<![.\w])(interface|enum|type|namespace|module)\s+\w+")
METHOD_NAME = re.compile(r"(\w+)\s*(?:<[^<>]*>)?\s*$")


def check_header(source: str, comment: str) -> list[str]:
    """Reports a missing SPDX or copyright line in the first lines of a file.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    head = source.splitlines()[:4]
    if f"{comment} {SPDX}" in head and f"{comment} {COPYRIGHT}" in head:
        return []
    return ["missing licence header (SPDX and copyright lines)"]


@dataclass
class Frame:
    """One open brace: what kind of body it is and the record components it declares.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    kind: str
    components: set[str] = field(default_factory=set)
    in_enum_constants: bool = False


@dataclass
class Scanner:
    """Walks C-like source once, skipping strings and comments, and hands out member headers.

    A header is the text of a declaration at member level: everything since the previous
    statement boundary up to the brace or semicolon that ends it.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """

    source: str
    template_strings: bool
    position: int = 0
    header: list[str] = field(default_factory=list)
    pending_doc: str = ""

    def comment_or_string(self) -> bool:
        """Consumes a comment or literal at the cursor, keeping the last doc comment.

        Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
        """
        text = self.source
        start = self.position
        if text.startswith("/*", start):
            end = text.find("*/", start + 2)
            end = len(text) if end < 0 else end + 2
            if text.startswith("/**", start):
                self.pending_doc = text[start:end]
            self.position = end
            return True
        if text.startswith("//", start):
            end = text.find("\n", start)
            self.position = len(text) if end < 0 else end
            return True
        if text.startswith('"""', start):
            end = text.find('"""', start + 3)
            self.position = len(text) if end < 0 else end + 3
            self.header.append('""')
            return True
        quote = text[start]
        if quote in "\"'" or (quote == "`" and self.template_strings):
            index = start + 1
            while index < len(text) and text[index] != quote:
                index += 2 if text[index] == "\\" else 1
            self.position = index + 1
            self.header.append('""')
            return True
        return False


def strip_annotations(header: str) -> str:
    """Removes annotations and decorators so their parentheses are not taken for parameters.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    previous = None
    while previous != header:
        previous = header
        header = ANNOTATION.sub(" ", header)
    return " ".join(header.split())


def record_components(header: str) -> set[str]:
    """Returns the component names of a record header such as `record Point(int x, int y)`.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    match = re.search(r"record\s+\w+\s*(?:<[^>]*>)?\s*\(([^)]*)\)", header)
    if not match or not match.group(1).strip():
        return set()
    return {part.split()[-1] for part in match.group(1).split(",") if part.split()}


def describe_java(header: str, frame: Frame | None, closer: str) -> tuple[str | None, str, set[str]]:
    """Classifies a Java member header as a type, a method or something exempt.

    Returns the description to report (or None if exempt), the kind of body it opens,
    and the record components when it declares a record.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    raw = " ".join(header.split())
    clean = strip_annotations(raw)
    type_match = JAVA_TYPE.search(clean)
    if type_match and "(" not in clean.split(type_match.group(0))[0]:
        kind = "enum" if type_match.group(1) == "enum" else "type"
        return f"{type_match.group(1)} {type_match.group(2)}", kind, record_components(clean)
    if frame is None or frame.kind not in ("type", "enum"):
        return None, "block", set()
    if "(" not in clean:
        if closer == "{" and frame.components and re.search(r"\b[A-Z]\w*$", clean):
            return f"compact constructor {clean.split()[-1]}", "block", set()
        return None, "block", set()
    before_paren = clean.split("(", 1)[0]
    if "=" in before_paren or "->" in before_paren:
        return None, "block", set()
    name_match = METHOD_NAME.search(before_paren)
    name = name_match.group(1) if name_match else before_paren.strip()
    if TEST_ANNOTATIONS.search(raw):
        return None, "block", set()
    has_no_parameters = re.search(r"\(\s*\)", clean) is not None
    if name in frame.components and has_no_parameters:
        return None, "block", set()
    return f"method {name}", "block", set()


def check_java(source: str, path: str) -> list[str]:
    """Lists the Java types and methods in a file that do not name their author.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    problems = [f"{path}: {p}" for p in check_header(source, "//")]
    scanner = Scanner(source, template_strings=False)
    stack: list[Frame] = []
    parens = 0
    while scanner.position < len(source):
        if scanner.comment_or_string():
            continue
        char = source[scanner.position]
        scanner.position += 1
        if char in "([":
            parens += 1
        elif char in ")]":
            parens -= 1
        if parens > 0 and char in "{}":
            scanner.header.append(char)
            continue
        if char in "{;}":
            frame = stack[-1] if stack else None
            header = "".join(scanner.header).strip()
            scanner.header = []
            if frame is not None and frame.in_enum_constants:
                if char == ";":
                    frame.in_enum_constants = False
                    scanner.pending_doc = ""
                    continue
                if char == "{":
                    stack.append(Frame("block"))
                    continue
            if char == "}":
                if stack:
                    stack.pop()
                scanner.pending_doc = ""
                continue
            member_level = frame is None or frame.kind in ("type", "enum")
            description, kind, components = (None, "block", set())
            if member_level and header:
                description, kind, components = describe_java(header, frame, char)
                if description and AUTHOR_TAG not in scanner.pending_doc:
                    problems.append(f"{path}: {description} has no Javadoc naming the author")
            scanner.pending_doc = ""
            if char == "{":
                new_frame = Frame("type" if kind == "type" else kind, components)
                new_frame.in_enum_constants = kind == "enum"
                stack.append(new_frame)
            continue
        scanner.header.append(char)
    return problems


def describe_typescript(header: str, frame: Frame | None) -> tuple[str | None, str]:
    """Classifies a TypeScript header as a class, a method, an exported function or exempt.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    clean = strip_annotations(" ".join(header.split()))
    class_match = TS_CLASS.search(clean)
    if class_match and not clean.startswith("interface"):
        return f"class {class_match.group(1)}", "type"
    if frame is None:
        function_match = TS_FUNCTION.search(clean)
        if function_match:
            return f"function {function_match.group(1)}", "block"
        if TS_INTERFACE.search(clean):
            return None, "interface"
        return None, "block"
    if frame.kind != "type" or "(" not in clean:
        return None, "block"
    before_paren = clean.split("(", 1)[0]
    if "=" in before_paren or "=>" in clean.split(")")[-1] and "=" in clean:
        return None, "block"
    name_match = METHOD_NAME.search(before_paren)
    name = name_match.group(1) if name_match else before_paren.strip()
    return f"method {name}", "block"


def check_typescript(source: str, path: str) -> list[str]:
    """Lists the TypeScript classes, methods and exported functions that do not name their author.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    problems = [f"{path}: {p}" for p in check_header(source, "//")]
    scanner = Scanner(source, template_strings=True)
    stack: list[Frame] = []
    parens = 0
    while scanner.position < len(source):
        if scanner.comment_or_string():
            continue
        char = source[scanner.position]
        scanner.position += 1
        if char in "([":
            parens += 1
        elif char in ")]":
            parens -= 1
        if parens > 0 and char in "{}":
            scanner.header.append(char)
            continue
        if char in "{;}":
            frame = stack[-1] if stack else None
            header = "".join(scanner.header).strip()
            scanner.header = []
            if char == "}":
                if stack:
                    stack.pop()
                scanner.pending_doc = ""
                continue
            member_level = frame is None or frame.kind == "type"
            description, kind = (None, "block")
            if member_level and header:
                description, kind = describe_typescript(header, frame)
                if description and AUTHOR_TAG not in scanner.pending_doc:
                    problems.append(f"{path}: {description} has no TSDoc naming the author")
            elif header and re.search(r"(=|=>|\breturn)\s*$", header):
                kind = "block"
            scanner.pending_doc = ""
            if char == "{":
                stack.append(Frame(kind))
            continue
        scanner.header.append(char)
    return problems


def check_python(source: str, path: str) -> list[str]:
    """Lists the Python module, classes and functions whose docstring does not end with the author.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    problems = [f"{path}: {p}" for p in check_header(source, "#")]
    tree = ast.parse(source, filename=path)
    nodes: list[ast.AST] = [tree]
    nodes += [n for n in ast.walk(tree) if isinstance(n, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef))]
    for node in nodes:
        docstring = ast.get_docstring(node) or ""  # type: ignore[arg-type]
        lines = [line.strip() for line in docstring.strip().splitlines()]
        if not lines or lines[-1] != PYTHON_AUTHOR_LINE:
            name = "module" if isinstance(node, ast.Module) else getattr(node, "name", "?")
            problems.append(f"{path}: {name} has no docstring ending with the author line")
    return problems


def tracked_sources(root: Path) -> list[Path]:
    """Returns the Java, TypeScript and Python files git knows about, minus generated ones.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    listing = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard"],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.splitlines()
    wanted = (".java", ".ts", ".py")
    return [
        root / name
        for name in listing
        if name.endswith(wanted)
        and not name.endswith(".d.ts")
        and not EXCLUDED_PARTS.intersection(Path(name).parts)
        and (root / name).is_file()
    ]


def check_file(path: Path) -> list[str]:
    """Runs the checker that matches a file's language.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    source = path.read_text(encoding="utf-8")
    if path.suffix == ".java":
        return check_java(source, str(path))
    if path.suffix == ".ts":
        return check_typescript(source, str(path))
    return check_python(source, str(path))


def main(arguments: list[str]) -> int:
    """Checks the given files, or every tracked source file, and prints each problem.

    Author: Ignacio Rodríguez Caro <rnachoccontact@gmail.com>
    """
    paths = [Path(a) for a in arguments] or tracked_sources(Path.cwd())
    problems = [problem for path in paths for problem in check_file(path)]
    for problem in problems:
        print(problem)
    print(f"check_authorship: {len(paths)} files, {len(problems)} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
