from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.ci.check_harness_links import HarnessLinkAuditor


class HarnessLinkAuditorTest(unittest.TestCase):
    def test_planning_artifact_names_in_skills_are_not_rule_references(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            skill = root / ".agents/skills/example/SKILL.md"
            skill.parent.mkdir(parents=True)
            skill.write_text(
                "Read `proposal.md`, `design.md`, `tasks.md`, `spec.md`, and `verification.md`.\n",
                encoding="utf-8",
            )
            auditor = HarnessLinkAuditor(root=root, strict=True)

            self.assertEqual(0, auditor.run())
            self.assertEqual([], auditor.findings)

    def test_unknown_markdown_name_in_skill_remains_a_dead_rule_reference(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            skill = root / ".agents/skills/example/SKILL.md"
            skill.parent.mkdir(parents=True)
            skill.write_text("Follow `missing-policy.md`.\n", encoding="utf-8")
            auditor = HarnessLinkAuditor(root=root, strict=True)

            self.assertEqual(1, auditor.run())
            self.assertEqual("missing-policy.md", auditor.findings[0].reference)

    def _vendored_catalog(self, root: Path) -> None:
        vendor = root / ".agents/vendor/rust-skills/skills"
        (vendor / "central-skill").mkdir(parents=True)
        (vendor / "central-skill/SKILL.md").write_text(
            "See the `excluded-skill` skill, when it is installed.\n", encoding="utf-8"
        )
        (vendor / "excluded-skill").mkdir()
        (vendor / "excluded-skill/SKILL.md").write_text("Excluded.\n", encoding="utf-8")
        (root / ".agents/skills").mkdir(parents=True)
        (root / ".agents/skills/central-skill").symlink_to("../vendor/rust-skills/skills/central-skill")

    def test_vendored_skill_may_name_an_unexposed_catalog_sibling(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._vendored_catalog(root)
            auditor = HarnessLinkAuditor(root=root, strict=True)

            self.assertEqual(0, auditor.run())
            self.assertEqual([], auditor.findings)

    def test_project_skill_naming_an_unexposed_catalog_skill_still_warns(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._vendored_catalog(root)
            skill = root / ".agents/skills/local-skill/SKILL.md"
            skill.parent.mkdir(parents=True)
            skill.write_text("Use the `excluded-skill` skill.\n", encoding="utf-8")
            auditor = HarnessLinkAuditor(root=root, strict=True)

            self.assertEqual(1, auditor.run())
            self.assertEqual("`excluded-skill` skill", auditor.findings[0].reference)


if __name__ == "__main__":
    unittest.main()
