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
            "    // SAFETY: checked above,\n"
            "    // and the length is fixed.\n"
            "    g();\n"
            "    // plain\n"
            "}\n"
        )
        self.assertEqual(sorted(sut.counted_lines("a.rs", source)), [1, 7, 8, 13])

    def test_kdoc_delimiters_of_a_cancel_safety_block_are_exempt(self) -> None:
        source = "/**\n * Binds the tunnel.\n *\n * # Cancel safety:\n * Not cancellable.\n */\nfun f() {}\n"
        self.assertEqual(sorted(sut.counted_lines("a.kt", source)), [2])

    def test_kotlin_raw_strings_and_kdoc(self) -> None:
        source = '/** KDoc. */\nval s = """\n// inside\n"""\nval t = 1 // why\n'
        self.assertEqual(sut.count_comments("a.kt", source), 2)

    def test_kotlin_template_with_nested_quotes_is_not_a_comment(self) -> None:
        source = 'val s = "[${g.replace("https://x/y", "")}]" // why\nval t = "${"a" + "}"}" /* b */\n'
        self.assertEqual(sorted(sut.counted_lines("a.kt", source)), [1, 2])

    def test_kotlin_raw_string_ending_in_four_quotes(self) -> None:
        source = 'val r = Regex("""name="([^"]+)"""")\nval s = """say "hi""""  // c\nval t = "http://x" // d\n'
        self.assertEqual(sorted(sut.counted_lines("a.kt", source)), [2, 3])

    def test_safety_run_does_not_cover_trailing_or_plain_heading_comments(self) -> None:
        source = (
            "// SAFETY: fd is open.\n"
            "let a = unsafe { f() }; // retry three times\n"
            "// plain note\n"
            "// # Safety\n"
            "// still plain\n"
        )
        self.assertEqual(sorted(sut.counted_lines("a.rs", source)), [2, 3, 4, 5])

    def test_python_uses_tokens_and_exempts_directives(self) -> None:
        source = '#!/usr/bin/env python3\n"""Docstring # not counted."""\nx = "#"  # noqa: E501\n# real\n'
        self.assertEqual(sut.count_comments("a.py", source), 1)

    def test_shell_counts_full_line_comments_only(self) -> None:
        source = "#!/bin/sh\n# shellcheck disable=SC2086\n# real\necho $# # trailing\n"
        self.assertEqual(sut.count_comments("a.sh", source), 1)


class CodeOnlyTests(unittest.TestCase):
    def test_comment_and_whitespace_edits_keep_code_equal(self) -> None:
        old = "/** Doc. */\nfn f() {\n    let x = 1; // why\n    // gone\n}\n"
        new = "fn f() {\n    let x = 1;\n}\n"
        self.assertEqual(sut.code_only("a.rs", old), sut.code_only("a.rs", new))

    def test_code_edit_is_detected(self) -> None:
        self.assertNotEqual(sut.code_only("a.kt", "val x = 1 // a\n"), sut.code_only("a.kt", "val x = 2\n"))

    def test_comment_text_inside_a_string_is_kept(self) -> None:
        old = 'let s = "// x"; // x\n'
        self.assertEqual(sut.code_only("a.rs", old), sut.code_only("a.rs", 'let s = "// x";\n'))
        self.assertNotEqual(sut.code_only("a.rs", old), sut.code_only("a.rs", 'let s = "";\n'))

    def test_whitespace_inside_strings_is_code(self) -> None:
        self.assertNotEqual(sut.code_only("a.rs", 'let s = "a b"; // x\n'), sut.code_only("a.rs", 'let s = "ab";\n'))

    def test_python_dedent_after_comment_removal_is_code(self) -> None:
        old = "for x in xs:\n    a(x)\n    # done\n    b()\n"
        self.assertNotEqual(sut.code_only("a.py", old), sut.code_only("a.py", "for x in xs:\n    a(x)\nb()\n"))
        self.assertEqual(sut.code_only("a.py", old), sut.code_only("a.py", "for x in xs:\n    a(x)\n    b()\n"))


class RatchetTests(unittest.TestCase):
    def test_growth_fails_and_shrink_is_reported(self) -> None:
        grown, shrunk = sut.compare({"a.rs": 3, "new.kt": 1}, {"a.rs": 2, "b.rs": 4})
        self.assertEqual([line.split(":")[0] for line in grown], ["a.rs", "new.kt"])
        self.assertEqual(shrunk, ["b.rs"])

    def test_raised_entries_allow_only_moves_from_the_own_source(self) -> None:
        old = {"a.rs": 2, "gone.rs": 5}
        renamed = {"renamed.rs": "gone.rs"}
        self.assertEqual(sut.raised_entries(old, {"a.rs": 2, "renamed.rs": 5}, renamed), [])
        self.assertEqual(len(sut.raised_entries(old, {"a.rs": 3, "gone.rs": 5}, {})), 1)
        self.assertEqual(len(sut.raised_entries(old, {"a.rs": 2, "unrelated.kt": 5}, {})), 1)
        split = {"part1.rs": "gone.rs", "part2.rs": "gone.rs"}
        self.assertEqual(sut.raised_entries(old, {"a.rs": 2, "part1.rs": 3, "part2.rs": 2}, split), [])
        self.assertEqual(len(sut.raised_entries(old, {"a.rs": 2, "part1.rs": 3, "part2.rs": 3}, split)), 1)

    def test_kotlin_line_join_is_code(self) -> None:
        old = "val total = base\n    - discount // fee\n"
        self.assertNotEqual(sut.code_only("a.kt", old), sut.code_only("a.kt", "val total = base - discount\n"))


if __name__ == "__main__":
    unittest.main()
