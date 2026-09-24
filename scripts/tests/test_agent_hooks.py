#!/usr/bin/env python3
"""Smoke tests for committed Claude Code and Codex hook wiring."""

from __future__ import annotations

import json
import re
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
POLICY = ROOT / ".agents/hooks/pre_tool_policy.py"
EXTRACTOR = ROOT / ".agents/hooks/extract_hook_paths.py"


def run(script: Path, payload: dict[str, object]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["python3", str(script)],
        input=json.dumps(payload),
        text=True,
        capture_output=True,
        check=False,
        cwd=ROOT,
    )


class HookPolicyTest(unittest.TestCase):
    def test_claude_edit_blocks_quality_baseline(self) -> None:
        result = run(
            POLICY,
            {"cwd": str(ROOT), "tool_input": {"file_path": "config/static/file-loc-baseline.json"}},
        )
        output = json.loads(result.stdout)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(output["decision"], "block")
        self.assertEqual(output["hookSpecificOutput"]["permissionDecision"], "deny")

    def test_codex_apply_patch_blocks_quality_baseline(self) -> None:
        result = run(
            POLICY,
            {
                "cwd": str(ROOT),
                "tool_input": {
                    "patch": "*** Begin Patch\n*** Update File: scripts/ci/module-deps-baseline.json\n@@\n*** End Patch"
                },
            },
        )
        self.assertEqual(json.loads(result.stdout)["decision"], "block")

    def test_absolute_path_blocks_when_agent_runs_from_subdirectory(self) -> None:
        result = run(
            POLICY,
            {
                "cwd": str(ROOT / "native/rust"),
                "tool_input": {"file_path": str(ROOT / "config/static/architecture-health-baseline.json")},
            },
        )
        self.assertEqual(json.loads(result.stdout)["decision"], "block")

    def test_non_quality_baseline_is_allowed(self) -> None:
        result = run(
            POLICY,
            {"cwd": str(ROOT), "tool_input": {"file_path": "benchmarks/performance-baseline.json"}},
        )
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def test_extractor_supports_structured_and_patch_paths(self) -> None:
        result = run(
            EXTRACTOR,
            {
                "tool_input": {
                    "file_path": "native/rust/crates/one/src/lib.rs",
                    "patch": "*** Update File: native/rust/crates/two/src/lib.rs\n",
                }
            },
        )
        self.assertEqual(
            result.stdout.splitlines(),
            ["native/rust/crates/one/src/lib.rs", "native/rust/crates/two/src/lib.rs"],
        )

    def test_committed_hook_manifests_cover_enforcement_events(self) -> None:
        # Claude Code edits arrive as Edit/Write; Codex edits arrive as apply_patch.
        expected_matchers = {".claude/settings.json": "Edit|Write", ".codex/hooks.json": "Edit|Write|apply_patch"}
        for relative, matcher in expected_matchers.items():
            hooks = json.loads((ROOT / relative).read_text())["hooks"]
            self.assertEqual(set(hooks), {"PreToolUse", "PostToolUse", "SubagentStop"})
            self.assertIn("pre_tool_policy.py", hooks["PreToolUse"][0]["hooks"][0]["command"])
            for event in ("PreToolUse", "PostToolUse"):
                self.assertEqual(hooks[event][0]["matcher"], matcher, f"{relative} {event}")

    def test_committed_hook_commands_reference_existing_scripts(self) -> None:
        for manifest in (ROOT / ".claude/settings.json", ROOT / ".codex/hooks.json"):
            for groups in json.loads(manifest.read_text())["hooks"].values():
                for handler in (hook for group in groups for hook in group["hooks"]):
                    for script in re.findall(r'\$ROOT/([^"\\\s]+)', handler["command"]):
                        self.assertTrue((ROOT / script).is_file(), f"{manifest.name}: missing {script}")


if __name__ == "__main__":
    unittest.main()
