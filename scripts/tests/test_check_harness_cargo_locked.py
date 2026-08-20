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


if __name__ == "__main__":
    unittest.main()
