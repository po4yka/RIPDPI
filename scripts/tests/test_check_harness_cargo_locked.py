from __future__ import annotations

import unittest

from scripts.ci import check_harness_cargo_locked as checker


class HarnessCargoLockedTest(unittest.TestCase):
    def test_central_skill_vendor_tree_is_excluded(self) -> None:
        vendor_file = (
            checker.REPO_ROOT
            / ".agents"
            / "vendor"
            / "rust-skills"
            / "skills"
            / "cargo-workflows"
            / "SKILL.md"
        )

        self.assertTrue(checker.is_excluded(vendor_file))

    def test_project_harness_content_remains_in_scope(self) -> None:
        project_file = checker.REPO_ROOT / ".agents" / "skills" / "compose" / "SKILL.md"

        self.assertFalse(checker.is_excluded(project_file))

    def test_archive_runs_need_no_lock_but_builds_do(self) -> None:
        self.assertFalse(checker.requires_lock('cargo nextest run --archive-file "$archive" -E all()'))
        self.assertTrue(checker.requires_lock("cargo nextest run --workspace -E all()"))
        self.assertFalse(checker.requires_lock("cargo nextest run --locked --workspace"))
        self.assertTrue(checker.requires_lock('cargo nextest archive --workspace --archive-file "$archive"'))
        self.assertTrue(checker.requires_lock('cargo test --archive-file "$archive"'))
        line = 'cargo nextest archive --workspace --archive-file "$archive"'
        self.assertEqual(1, len(checker.CARGO_COMMAND.findall(line)))


if __name__ == "__main__":
    unittest.main()
