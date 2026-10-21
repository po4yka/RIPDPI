"""Approval boundary tests for native size growth policy changes."""
from __future__ import annotations

import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/ci/check_native_size_policy.py"
spec = importlib.util.spec_from_file_location("native_size_policy", SCRIPT)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class NativeSizePolicyTest(unittest.TestCase):
    def setUp(self):
        self.base = json.loads((ROOT / guard.POLICY_PATH).read_text())
        # Keep the approved historical policy transition stable after integration.
        self.base.update(maxPerLibraryGrowthBytes=393216, maxTotalGrowthPercent=2.0, maxTotalGrowthBytes=1572864)
        self.head = copy.deepcopy(self.base)
        self.head.update(maxPerLibraryGrowthBytes=557056, maxTotalGrowthPercent=3.4, maxTotalGrowthBytes=2621440)
        self.head["_comment"] += " Limit increase approved by the user on 2026-10-21."

    def test_authorized_limit_increase_preserves_all_eight_measurements(self):
        self.assertEqual(8, sum(map(len, self.head["libraries"].values())))
        guard.validate_delta(self.base, self.head)

    def test_each_measurement_change_is_rejected(self):
        for abi, libraries in self.head["libraries"].items():
            for library in libraries:
                with self.subTest(abi=abi, library=library):
                    changed = copy.deepcopy(self.head)
                    changed["libraries"][abi][library] += 1
                    with self.assertRaisesRegex(ValueError, "measurements"):
                        guard.validate_delta(self.base, changed)

    def test_measurement_type_change_and_comment_only_change_are_rejected(self):
        changed = copy.deepcopy(self.head)
        changed["libraries"]["arm64-v8a"]["libripdpi.so"] = float(changed["libraries"]["arm64-v8a"]["libripdpi.so"])
        with self.assertRaisesRegex(ValueError, "measurements"):
            guard.validate_delta(self.base, changed)
        changed = copy.deepcopy(self.base)
        changed["_comment"] += " Append only without a policy increase."
        with self.assertRaisesRegex(ValueError, "increase"):
            guard.validate_delta(self.base, changed)

    def test_comment_rewrite_and_empty_append_are_rejected(self):
        for comment in ("Replaced provenance", self.base["_comment"], self.base["_comment"] + "  "):
            changed = copy.deepcopy(self.head)
            changed["_comment"] = comment
            with self.assertRaisesRegex(ValueError, "provenance"):
                guard.validate_delta(self.base, changed)

    def test_added_field_or_abi_is_rejected(self):
        for changed in (dict(self.head, unexpected=True), copy.deepcopy(self.head)):
            if "unexpected" not in changed:
                changed["libraries"]["new-abi"] = {}
            with self.assertRaises(ValueError):
                guard.validate_delta(self.base, changed)

    def test_invalid_and_reduced_limits_are_rejected(self):
        for key in guard.LIMITS:
            for value in (None, True, "999999999", -1, 0, float("nan"), float("inf"), self.base[key] - 1):
                with self.subTest(key=key, value=value):
                    changed = dict(self.head, **{key: value})
                    with self.assertRaises(ValueError):
                        guard.validate_delta(self.base, changed)

    def test_missing_label_and_similar_label_are_rejected(self):
        for labels in ([], [{"name": guard.APPROVAL_LABEL + "-other"}]):
            with self.assertRaisesRegex(ValueError, "label"):
                guard.approved_base({"pull_request": {"labels": labels, "base": {"sha": "a" * 40}}})

    def test_label_does_not_replace_exact_base_sha(self):
        for base in ("main", "--all", "a" * 39):
            with self.assertRaisesRegex(ValueError, "base SHA"):
                guard.approved_base({"pull_request": {"labels": [{"name": guard.APPROVAL_LABEL}], "base": {"sha": base}}})

    def test_duplicate_json_keys_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            json.loads('{"x": 1, "x": 2}', object_pairs_hook=guard.unique_object)

    def test_actual_workflow_guard_keeps_other_baselines_banned(self):
        source = (ROOT / ".github/workflows/ci.yml").read_text()
        step = source.split("      - name: Fail if baseline files modified\n", 1)[1].split("\n      - uses:", 1)[0]
        run = step.split("        run: |\n", 1)[1]
        run = "\n".join(line[10:] for line in run.splitlines())
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for args in (["init", "-q"], ["config", "user.name", "Test"], ["config", "user.email", "test@example.invalid"]):
                subprocess.run(["git", *args], cwd=root, check=True)
            policy = root / guard.POLICY_PATH
            policy.parent.mkdir(parents=True)
            policy.write_text(json.dumps(self.base))
            shutil.copy(SCRIPT, root / "scripts/ci/check_native_size_policy.py")
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "commit", "-qm", "chore: establish policy test base"], cwd=root, check=True)
            base = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
            policy.write_text(json.dumps(self.head))
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(["git", "commit", "-qm", "chore: update approved size policy"], cwd=root, check=True)
            event = root / "event.json"
            event.write_text(json.dumps({"pull_request": {"base": {"sha": base}, "labels": [{"name": guard.APPROVAL_LABEL}]}}))
            environment = {**os.environ, "GITHUB_EVENT_PATH": str(event), "PR_BASE_SHA": base}
            result = subprocess.run(["bash", "-eo", "pipefail", "-c", run], cwd=root, env=environment, text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr + result.stdout)
            forbidden = root / "other-baseline.json"
            forbidden.write_text("{}")
            subprocess.run(["git", "add", "other-baseline.json"], cwd=root, check=True)
            subprocess.run(["git", "commit", "-qm", "chore: add forbidden baseline"], cwd=root, check=True)
            result = subprocess.run(["bash", "-eo", "pipefail", "-c", run], cwd=root, env=environment, text=True, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("baseline files must not be modified", result.stdout)


if __name__ == "__main__":
    unittest.main()
