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


if __name__ == "__main__":
    unittest.main()
