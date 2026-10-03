from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.ci import check_harness_manifests as manifests


class SkillMirrorTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.environment = {
            key: value for key, value in os.environ.items() if not key.startswith("GIT_")
        }
        self.environment.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
        self.git("init", "--initial-branch=main")
        self.canonical = self.root / ".agents/skills"
        (self.canonical / "shared").mkdir(parents=True)
        (self.canonical / "shared/SKILL.md").write_text("Shared\n", encoding="utf-8")
        self.mirrors = (self.root / ".claude/skills", self.root / ".github/skills")
        for mirror in self.mirrors:
            mirror.mkdir(parents=True)
            (mirror / "shared").symlink_to("../../.agents/skills/shared")
        for name, value in [
            ("REPO_ROOT", self.root), ("CANONICAL_SKILLS", self.canonical),
            ("SKILL_MIRRORS", self.mirrors),
        ]:
            replacement = patch.object(manifests, name, value)
            replacement.start()
            self.addCleanup(replacement.stop)
        self.ignore(".claude/skills/legal-check/\n")

    def git(self, *arguments: str) -> str:
        return subprocess.run(
            ["git", "-C", str(self.root), *arguments], env=self.environment,
            text=True, capture_output=True, check=True,
        ).stdout

    def ignore(self, contents: str) -> None:
        (self.root / ".gitignore").write_text(contents, encoding="utf-8")
        self.git("add", ".gitignore")

    def local_skill(self, name: str = "legal-check", mirror: int = 0) -> Path:
        directory = self.mirrors[mirror] / name
        directory.mkdir()
        (directory / "SKILL.md").write_text("Local\n", encoding="utf-8")
        return directory

    def test_declared_untracked_local_skill_does_not_change_mirror_contract(self) -> None:
        local = self.local_skill()
        before = (local / "SKILL.md").read_bytes()
        manifests.validate_mirrors({"shared"})
        self.assertEqual(before, (local / "SKILL.md").read_bytes())
        self.assertEqual("", self.git("ls-files", "--", str(local)))

    def test_exact_anchored_rule_can_declare_a_local_copilot_skill(self) -> None:
        self.ignore("/.github/skills/personal/\n")
        self.local_skill("personal", mirror=1)
        manifests.validate_mirrors({"shared"})

    def test_unknown_extra_skill_still_fails(self) -> None:
        self.local_skill("unexpected")
        with self.assertRaisesRegex(ValueError, "extra=.*unexpected"):
            manifests.validate_mirrors({"shared"})

    def test_broad_ignore_pattern_does_not_hide_unknown_skills(self) -> None:
        self.ignore(".claude/skills/*\n")
        self.local_skill("unexpected")
        with self.assertRaisesRegex(ValueError, "extra=.*unexpected"):
            manifests.validate_mirrors({"shared"})

    def test_glob_named_directory_does_not_make_a_broad_rule_exact(self) -> None:
        self.ignore(".claude/skills/personal*/\n")
        self.local_skill("personal*")
        with self.assertRaisesRegex(ValueError, "extra=.*personal"):
            manifests.validate_mirrors({"shared"})

    def test_info_exclude_does_not_declare_repository_policy(self) -> None:
        self.ignore("# No declared local skills\n")
        (self.root / ".git/info/exclude").write_text(".claude/skills/legal-check/\n", encoding="utf-8")
        self.local_skill()
        with self.assertRaisesRegex(ValueError, "extra=.*legal-check"):
            manifests.validate_mirrors({"shared"})

    def test_untracked_gitignore_cannot_declare_local_skills(self) -> None:
        self.git("rm", "--cached", ".gitignore")
        self.local_skill()
        with self.assertRaisesRegex(ValueError, "extra=.*legal-check"):
            manifests.validate_mirrors({"shared"})

    def test_tracked_files_in_local_directory_still_fail(self) -> None:
        local = self.local_skill()
        self.git("add", "--force", str(local / "SKILL.md"))
        with self.assertRaisesRegex(ValueError, "extra=.*legal-check"):
            manifests.validate_mirrors({"shared"})

    def test_local_skill_cannot_shadow_a_canonical_skill(self) -> None:
        (self.canonical / "legal-check").mkdir()
        self.local_skill()
        (self.mirrors[1] / "legal-check").symlink_to("../../.agents/skills/legal-check")
        with self.assertRaisesRegex(ValueError, "mirror entry must be a symlink"):
            manifests.validate_mirrors({"shared", "legal-check"})

    def test_local_skill_does_not_allow_missing_shared_mirror(self) -> None:
        self.local_skill()
        (self.mirrors[0] / "shared").unlink()
        with self.assertRaisesRegex(ValueError, "missing=.*shared"):
            manifests.validate_mirrors({"shared"})

    def test_shared_mirror_must_resolve_to_the_correct_skill(self) -> None:
        self.local_skill()
        (self.mirrors[0] / "shared").unlink()
        (self.mirrors[0] / "shared").symlink_to("../../.agents/skills/other")
        (self.canonical / "other").mkdir()
        with self.assertRaisesRegex(ValueError, "resolves to"):
            manifests.validate_mirrors({"shared"})


if __name__ == "__main__":
    unittest.main()
