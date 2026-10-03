from __future__ import annotations

import tempfile
import os
import subprocess
import re
from unittest.mock import patch
import unittest
from pathlib import Path

from scripts.ci.check_harness_links import HarnessLinkAuditor, SOURCE_EXTENSIONS


class HarnessLinkAuditorTest(unittest.TestCase):
    def _source_repo(self, root: Path, instruction: str, sources: dict[str, str]) -> None:
        environment = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        subprocess.run(["git", "init", str(root)], env=environment, capture_output=True, check=True)
        (root / "AGENTS.md").write_text(instruction, encoding="utf-8")
        for name, contents in sources.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(contents, encoding="utf-8")
        subprocess.run(["git", "-C", str(root), "add", "."], env=environment, capture_output=True, check=True)

    def test_missing_source_file_is_dead(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._source_repo(root, "Read `native/rust/removed.rs`.\n", {"native/rust/live.rs": "fn live() {}"})
            auditor = HarnessLinkAuditor(root, strict=True)
            self.assertEqual(1, auditor.run())
            self.assertEqual("native/rust/removed.rs", auditor.findings[0].reference)

    def test_missing_symbol_is_dead_even_if_mentioned_in_comments_or_strings(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._source_repo(root, "Read `native/live.rs:removed()`.\n", {
                "native/live.rs": '// fn removed() {}\n/* fn removed() {} */\nconst DOC: &str = "fn removed() {}";\nfn live() {}',
            })
            auditor = HarnessLinkAuditor(root, strict=True)
            self.assertEqual(1, auditor.run())
            self.assertIn("removed", auditor.findings[0].expected_path)

    def test_live_source_and_rust_kotlin_python_symbols_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._source_repo(root, "Read `native/live.rs:live()`, `Screen.kt#Screen`, and [parser](scripts/parser.py#parse).\n", {
                "native/live.rs": 'pub(crate) async fn live() {}',
                "app/Screen.kt": 'data class Screen(val value: String)',
                "scripts/parser.py": 'def parse():\n    return 1\n',
            })
            self.assertEqual(0, HarnessLinkAuditor(root, strict=True).run())

    def test_symbol_requires_an_unambiguous_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._source_repo(root, "Read `lib.rs:live()`.\n", {
                "native/a/lib.rs": 'fn live() {}', "native/b/lib.rs": 'fn other() {}',
            })
            auditor = HarnessLinkAuditor(root, strict=True)
            self.assertEqual(1, auditor.run())
            self.assertIn("AMBIGUOUS", auditor.findings[0].reason)

    def test_partial_paths_and_ellipsis_resolve_but_wrong_root_paths_do_not(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._source_repo(root, "Read `crate/src/lib.rs` and `app/.../Screen.kt`.\n", {
                "native/crate/src/lib.rs": 'fn live() {}', "app/ui/Screen.kt": 'class Screen',
            })
            self.assertEqual(0, HarnessLinkAuditor(root, strict=True).run())
            (root / "AGENTS.md").write_text("Read `app/wrong/Screen.kt`.\n", encoding="utf-8")
            self.assertEqual(1, HarnessLinkAuditor(root, strict=True).run())

    def test_deleted_tracked_source_and_untracked_replacement_do_not_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._source_repo(root, "Read `native/live.rs`.\n", {"native/live.rs": 'fn live() {}'})
            (root / "native/live.rs").unlink()
            (root / "native/untracked.rs").write_text("fn live() {}", encoding="utf-8")
            self.assertEqual(1, HarnessLinkAuditor(root, strict=True).run())
            (root / "AGENTS.md").write_text("Read `native/untracked.rs`.\n", encoding="utf-8")
            self.assertEqual(1, HarnessLinkAuditor(root, strict=True).run())

    def test_codex_agent_source_references_are_checked(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._source_repo(root, "Project.\n", {
                ".codex/agents/example.toml": 'developer_instructions = "Read `native/missing.rs`."',
                "native/live.rs": 'fn live() {}',
            })
            auditor = HarnessLinkAuditor(root, strict=True)
            self.assertEqual(1, auditor.run())
            self.assertEqual(".codex/agents/example.toml", auditor.findings[0].source_file)

    def test_skill_reference_markdown_is_checked(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._source_repo(root, "Project.\n", {
                ".agents/skills/example/SKILL.md": 'Skill.\n',
                ".agents/skills/example/references/pipeline.md": 'Read `native/missing.rs`.\n',
                "native/live.rs": 'fn live() {}',
            })
            auditor = HarnessLinkAuditor(root, strict=True)
            self.assertEqual(1, auditor.run())
            self.assertTrue(auditor.findings[0].source_file.endswith("references/pipeline.md"))

    def test_line_coordinates_and_ranges_are_checked(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._source_repo(root, "Read `native/live.rs:1-2` and [live](native/live.rs#L1-L2).\n", {
                "native/live.rs": 'fn live() {\n}\n',
            })
            self.assertEqual(0, HarnessLinkAuditor(root, strict=True).run())
            for reference in ("native/live.rs:0", "native/live.rs:3", "native/live.rs#L2-L1", "native/missing.rs:42"):
                with self.subTest(reference=reference):
                    (root / "AGENTS.md").write_text(f"Read `{reference}`.\n", encoding="utf-8")
                    self.assertEqual(1, HarnessLinkAuditor(root, strict=True).run())

    def test_nested_comments_and_raw_strings_are_not_symbol_definitions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._source_repo(root, "Read `native/live.rs:removed()`.\n", {
                "native/live.rs": '/* outer /* nested */ fn removed() {} */\nconst DOC: &str = r###"fn removed() {}"###;\nfn live() {}',
            })
            self.assertEqual(1, HarnessLinkAuditor(root, strict=True).run())

    def test_templates_fences_and_external_paths_are_not_local_source_pointers(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._source_repo(root, 'Read `src/<Name>.kt`, `.gradle.kts`, and `../other/source.py`.\n```rust\nRead `missing.rs`.\n```\n', {})
            auditor = HarnessLinkAuditor(root, strict=True)
            self.assertEqual(0, auditor.run())
            self.assertEqual(0, auditor.source_references)

    def test_source_extensions_trigger_ci_and_precommit_is_blocking(self) -> None:
        root = Path(__file__).resolve().parents[2]
        workflow = (root / ".github/workflows/harness-checks.yml").read_text(encoding="utf-8")
        for event in ("pull_request", "push"):
            block = re.search(rf"(?ms)^  {event}:\n(.*?)(?=^  \w|^permissions:)", workflow)
            self.assertIsNotNone(block)
            for extension in SOURCE_EXTENSIONS:
                self.assertIn(f"'**/*.{extension}'", block.group(1))
        hooks = (root / "lefthook.yml").read_text(encoding="utf-8")
        self.assertIn("harness-link-audit:\n      run: python3 scripts/ci/check_harness_links.py --strict", hooks)

    def test_external_library_scope_does_not_exempt_repository_root_paths(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._source_repo(root, 'Project.\n', {
                ".agents/skills/library/SKILL.md": '<!-- harness-source-scope: external -->\nRead `Upstream.kt`.\n',
                ".agents/skills/library/references/api.md": 'Read `app/missing.kt`.\n',
                "app/Screen.kt": 'class Screen',
            })
            auditor = HarnessLinkAuditor(root, strict=True)
            self.assertEqual(1, auditor.run())
            self.assertEqual(1, auditor.external_source_references)
            self.assertEqual('app/missing.kt', auditor.findings[0].reference)

    def test_relative_markdown_links_inside_checkout_are_checked(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._source_repo(root, 'Project.\n', {
                ".agents/skills/example/SKILL.md": 'Read [live](../../../native/live.rs#live).\n',
                "native/live.rs": 'fn live() {}',
            })
            self.assertEqual(0, HarnessLinkAuditor(root, strict=True).run())
            (root / ".agents/skills/example/SKILL.md").write_text('Read [missing](../../../native/missing.rs#live).\n', encoding="utf-8")
            self.assertEqual(1, HarnessLinkAuditor(root, strict=True).run())

    def test_external_scope_still_checks_relative_links_to_root_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._source_repo(root, 'Project.\n', {
                ".agents/skills/library/SKILL.md": '<!-- harness-source-scope: external -->\n[build](../../../build.gradle.kts)\n[missing](../../../missing.py)\n',
                "build.gradle.kts": 'val enabled = true',
            })
            auditor = HarnessLinkAuditor(root, strict=True)
            self.assertEqual(1, auditor.run())
            self.assertEqual(2, auditor.source_references)
            self.assertEqual(0, auditor.external_source_references)

    def test_markdown_link_does_not_resolve_by_suffix_elsewhere(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._source_repo(root, '[screen](Screen.kt)\n', {"app/Screen.kt": 'class Screen'})
            self.assertEqual(1, HarnessLinkAuditor(root, strict=True).run())

    def test_git_environment_cannot_redirect_source_inventory(self) -> None:
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as other:
            root = Path(directory)
            self._source_repo(root, 'Read `native/live.rs:live()`.\n', {"native/live.rs": 'fn live() {}'})
            self._source_repo(Path(other), 'Other.\n', {})
            with patch.dict(os.environ, {"GIT_DIR": str(Path(other) / ".git"), "GIT_WORK_TREE": other, "GIT_INDEX_FILE": str(Path(other) / ".git/index")}):
                self.assertEqual(0, HarnessLinkAuditor(root, strict=True).run())

    def test_tilde_and_nested_backtick_fences_are_examples(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._source_repo(root, '~~~~md\nRead `missing.rs`.\n~~~~\n````md\n```rust\nRead `missing.rs`.\n```\n````\n', {})
            self.assertEqual(0, HarnessLinkAuditor(root, strict=True).run())

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
