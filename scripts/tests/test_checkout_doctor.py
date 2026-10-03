from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "checkout_doctor.py"


class CheckoutDoctorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name).resolve()
        self.environment = {
            key: value for key, value in os.environ.items() if not key.startswith("GIT_")
        }
        self.environment.update(
            GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull,
            GIT_AUTHOR_NAME="Test", GIT_AUTHOR_EMAIL="test@example.com",
            GIT_COMMITTER_NAME="Test", GIT_COMMITTER_EMAIL="test@example.com",
        )
        self.root = self.directory / "checkout with spaces"
        self.git(self.directory, "init", "--initial-branch=main", str(self.root))
        (self.root / "tracked.txt").write_text("tracked\n", encoding="utf-8")
        self.git(self.root, "add", "tracked.txt")
        self.git(self.root, "commit", "-m", "Initial")

    def git(self, path: Path, *arguments: str) -> str:
        return subprocess.run(
            ["git", "-C", str(path), *arguments], env=self.environment,
            capture_output=True, text=True, check=True,
        ).stdout.strip()

    def diagnose(self, path: Path) -> tuple[int, dict]:
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--json", str(path)],
            env=self.environment, capture_output=True, text=True, check=False,
        )
        self.assertEqual("", result.stderr)
        return result.returncode, json.loads(result.stdout)

    def test_nested_directory_discovers_actual_checkout(self) -> None:
        nested = self.root / "nested/directory"
        nested.mkdir(parents=True)
        code, report = self.diagnose(nested)
        self.assertEqual(0, code)
        self.assertEqual(str(self.root), report["root"])
        self.assertEqual("main", report["branch"])
        self.assertEqual(self.git(self.root, "rev-parse", "HEAD"), report["head"])
        self.assertEqual(str(self.root / ".git"), report["common_git_dir"])

    def test_linked_worktree_reports_its_branch_and_shared_git_directory(self) -> None:
        linked = self.directory / "linked tree"
        self.git(self.root, "worktree", "add", "-b", "job", str(linked))
        code, report = self.diagnose(linked)
        self.assertEqual(0, code)
        self.assertEqual(str(linked), report["root"])
        self.assertEqual("job", report["branch"])
        self.assertNotEqual(report["git_dir"], report["common_git_dir"])
        self.assertEqual(str(self.root / ".git"), report["common_git_dir"])
        self.assertEqual({str(self.root), str(linked)}, {
            entry["path"] for entry in report["worktrees"] if entry["usable"]
        })

    def test_bare_repository_lists_usable_worktree_without_changing_config(self) -> None:
        bare = self.directory / "repository.git"
        linked = self.directory / "actual source"
        self.git(self.directory, "clone", "--bare", str(self.root), str(bare))
        self.git(bare, "worktree", "add", "-b", "job", str(linked))
        before = (bare / "config").read_bytes()
        code, report = self.diagnose(bare)
        self.assertEqual(1, code)
        self.assertTrue(report["bare"])
        self.assertIsNone(report["root"])
        self.assertIn(str(linked), [
            entry["path"] for entry in report["worktrees"] if entry["usable"]
        ])
        self.assertEqual(before, (bare / "config").read_bytes())

    def test_moved_worktree_uses_current_registration(self) -> None:
        old = self.directory / "old tree"
        moved = self.directory / "moved tree"
        self.git(self.root, "worktree", "add", "-b", "job", str(old))
        self.git(self.root, "worktree", "move", str(old), str(moved))
        code, report = self.diagnose(moved)
        self.assertEqual(0, code)
        self.assertEqual(str(moved), report["root"])
        self.assertNotIn(str(old), [entry["path"] for entry in report["worktrees"]])

    def test_repository_environment_cannot_redirect_checkout_discovery(self) -> None:
        linked = self.directory / "linked tree"
        self.git(self.root, "worktree", "add", "-b", "job", str(linked))
        self.environment.update(GIT_DIR=str(self.root / ".git"), GIT_WORK_TREE=str(self.root))
        code, report = self.diagnose(linked)
        self.assertEqual(0, code)
        self.assertEqual(str(linked), report["root"])
        self.assertEqual("job", report["branch"])
        self.assertEqual(["GIT_DIR", "GIT_WORK_TREE"], report["ignored_git_environment"])
        self.assertEqual(str(self.root), self.environment["GIT_WORK_TREE"])

    def test_detached_head_is_a_valid_checkout(self) -> None:
        self.git(self.root, "checkout", "--detach")
        code, report = self.diagnose(self.root)
        self.assertEqual(0, code)
        self.assertIsNone(report["branch"])
        self.assertTrue(report["head"])

    def test_missing_worktree_is_not_a_usable_candidate(self) -> None:
        linked = self.directory / "deleted tree"
        self.git(self.root, "worktree", "add", "-b", "job", str(linked))
        linked.rename(self.directory / "unregistered tree")
        code, report = self.diagnose(self.root)
        self.assertEqual(0, code)
        entry = next(entry for entry in report["worktrees"] if entry["path"] == str(linked))
        self.assertFalse(entry["usable"])
        self.assertFalse(entry["exists"])

    def test_incorrect_bare_config_is_reported_without_repair(self) -> None:
        linked = self.directory / "linked tree"
        self.git(self.root, "worktree", "add", "-b", "job", str(linked))
        self.git(self.root, "config", "core.bare", "true")
        before = (self.root / ".git/config").read_bytes()
        code, report = self.diagnose(self.root)
        self.assertEqual(1, code)
        self.assertIsNone(report["root"])
        self.assertTrue(report["issues"])
        self.assertIn(str(linked), [
            entry["path"] for entry in report["worktrees"] if entry["usable"]
        ])
        self.assertEqual(before, (self.root / ".git/config").read_bytes())

    def test_uninitialized_rust_skills_are_reported_without_initializing(self) -> None:
        vendor = self.directory / "skills source"
        self.git(self.directory, "clone", str(self.root), str(vendor))
        (vendor / "skills/example").mkdir(parents=True)
        (vendor / "skills/example/SKILL.md").write_text("Example\n", encoding="utf-8")
        self.git(vendor, "add", "skills")
        self.git(vendor, "commit", "-m", "Skills")
        self.git(self.root, "-c", "protocol.file.allow=always", "submodule", "add",
                 str(vendor), ".agents/vendor/rust-skills")
        self.git(self.root, "commit", "-am", "Vendor skills")
        clone = self.directory / "without submodules"
        self.git(self.directory, "clone", str(self.root), str(clone))
        code, report = self.diagnose(clone)
        self.assertEqual(1, code)
        self.assertEqual("uninitialized", report["rust_skills"]["state"])
        self.assertFalse((clone / ".agents/vendor/rust-skills/skills").exists())
        initialized_code, initialized = self.diagnose(self.root)
        self.assertEqual(0, initialized_code)
        self.assertEqual("ready", initialized["rust_skills"]["state"])

    def test_missing_directory_and_non_repository_return_structured_errors(self) -> None:
        for path in [self.directory, self.directory / "missing"]:
            with self.subTest(path=path):
                code, report = self.diagnose(path)
                self.assertEqual(1, code)
                self.assertIsNone(report["root"])
                self.assertTrue(report["issues"])


if __name__ == "__main__":
    unittest.main()
