from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ci"))

import check_comment_ratchet as sut


class CommentCountTests(unittest.TestCase):
    def test_rust_counts_line_block_and_doc_comments_but_not_strings(self) -> None:
        source = (
            "/// Doc line.\n"
            "fn f<'a>(x: &'a str) -> char {\n"
            '    let url = "http://example.com/*x*/";\n'
            '    let raw = r#"// not a comment"#;\n'
            "    let c = '\\''; // trailing\n"
            "    /* block\n"
            "       spans */\n"
            "    'x'\n"
            "}\n"
        )
        self.assertEqual(sut.count_comments("a.rs", source), 4)

    def test_safety_comments_and_safety_doc_sections_are_exempt(self) -> None:
        source = (
            "/// Reads a value.\n"
            "///\n"
            "/// # Safety\n"
            "/// The pointer must be valid.\n"
            "/// # Cancel safety\n"
            "/// Cancel safe.\n"
            "/// # Errors\n"
            "/// Fails on EOF.\n"
            "unsafe fn f() {\n"
            "    // SAFETY: checked above.\n"
            "}\n"
        )
        self.assertEqual(sorted(sut.counted_lines("a.rs", source)), [1, 2, 7, 8])

    def test_kotlin_raw_strings_and_kdoc(self) -> None:
        source = '/** KDoc. */\nval s = """\n// inside\n"""\nval t = 1 // why\n'
        self.assertEqual(sut.count_comments("a.kt", source), 2)

    def test_python_uses_tokens_and_exempts_directives(self) -> None:
        source = '#!/usr/bin/env python3\n"""Docstring # not counted."""\nx = "#"  # noqa: E501\n# real\n'
        self.assertEqual(sut.count_comments("a.py", source), 1)

    def test_shell_counts_full_line_comments_only(self) -> None:
        source = "#!/bin/sh\n# shellcheck disable=SC2086\n# real\necho $# # trailing\n"
        self.assertEqual(sut.count_comments("a.sh", source), 1)


class RatchetTests(unittest.TestCase):
    def test_growth_fails_and_shrink_is_reported(self) -> None:
        grown, shrunk = sut.compare({"a.rs": 3, "new.kt": 1}, {"a.rs": 2, "b.rs": 4})
        self.assertEqual([line.split(":")[0] for line in grown], ["a.rs", "new.kt"])
        self.assertEqual(shrunk, ["b.rs"])


if __name__ == "__main__":
    unittest.main()
