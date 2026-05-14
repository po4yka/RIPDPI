#!/usr/bin/env python3
"""Unit tests for scripts/ci/check_fleet_fixtures.py."""
from __future__ import annotations

import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


def load_module(module_name: str, relative_path: str):
    root = Path(__file__).resolve().parents[2]
    module_path = root / relative_path
    spec = importlib.util.spec_from_file_location(module_name, module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


fixtures = load_module(
    "check_fleet_fixtures",
    "scripts/ci/check_fleet_fixtures.py",
)


def _copy_fixture_tree(dst: Path) -> Path:
    """Copies the committed fleet-fixtures tree into ``dst`` for mutation."""
    import shutil

    root = fixtures.FIXTURES_ROOT
    target = dst / "fleet-fixtures"
    shutil.copytree(root, target)
    return target


class FleetFixturesHappyPathTest(unittest.TestCase):
    def test_committed_fixtures_pass(self) -> None:
        summary = fixtures.validate_fixtures(
            fixtures.FIXTURES_ROOT,
            fixtures.pinned_sha(),
        )
        self.assertEqual(sorted(fixtures.REQUIRED_SCENARIOS), summary["scenarios"])
        self.assertEqual(fixtures.pinned_sha(), summary["deployerGitSha"])

    def test_main_against_repo_fixtures(self) -> None:
        self.assertEqual(fixtures.main([]), 0)

    def test_pinned_sha_is_read_from_the_refresh_script(self) -> None:
        sha = fixtures.pinned_sha()
        self.assertTrue(sha)
        # The pin must be exactly what every committed meta.json carries.
        for scenario in fixtures.REQUIRED_SCENARIOS:
            meta = json.loads(
                (fixtures.FIXTURES_ROOT / scenario / "meta.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual(sha, meta["deployer_git_sha"])


class FleetFixturesDefectTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.tree = _copy_fixture_tree(self.tmp)
        self.sha = fixtures.pinned_sha()

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_missing_required_file_is_rejected(self) -> None:
        (self.tree / "p0-only" / "bundle.json").unlink()
        with self.assertRaisesRegex(ValueError, "p0-only.*bundle.json"):
            fixtures.validate_fixtures(self.tree, self.sha)

    def test_missing_expected_group_is_rejected(self) -> None:
        # The harness expects expected-group.json for every scenario here.
        (self.tree / "multi-cohort-p0-p1-p2a" / "expected-group.json").unlink()
        with self.assertRaisesRegex(ValueError, "expected-group.json"):
            fixtures.validate_fixtures(self.tree, self.sha)

    def test_missing_expected_routing_is_rejected(self) -> None:
        (self.tree / "per-app-bypass-and-via-tun" / "expected-routing.json").unlink()
        with self.assertRaisesRegex(ValueError, "expected-routing.json"):
            fixtures.validate_fixtures(self.tree, self.sha)

    def test_sha_mismatch_vs_script_pin_is_rejected(self) -> None:
        meta_path = self.tree / "p1-only" / "meta.json"
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        meta["deployer_git_sha"] = "deadbeef" * 5
        meta_path.write_text(json.dumps(meta), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "deployer_git_sha"):
            fixtures.validate_fixtures(self.tree, self.sha)

    def test_inconsistent_sha_across_scenarios_is_rejected(self) -> None:
        meta_path = self.tree / "p2a-hysteria-only" / "meta.json"
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        meta["deployer_git_sha"] = "1111111111111111111111111111111111111111-fixture"
        meta_path.write_text(json.dumps(meta), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "deployer_git_sha"):
            fixtures.validate_fixtures(self.tree, self.sha)

    def test_malformed_bundle_json_is_rejected(self) -> None:
        (self.tree / "p0-only" / "bundle.json").write_text(
            "{ not valid json", encoding="utf-8"
        )
        with self.assertRaisesRegex(ValueError, "p0-only.*bundle.json.*JSON"):
            fixtures.validate_fixtures(self.tree, self.sha)

    def test_malformed_meta_json_is_rejected(self) -> None:
        (self.tree / "p1-only" / "meta.json").write_text(
            "}}}", encoding="utf-8"
        )
        with self.assertRaisesRegex(ValueError, "meta.json.*JSON"):
            fixtures.validate_fixtures(self.tree, self.sha)

    def test_wrong_top_level_shape_is_rejected(self) -> None:
        # bundle.json must be a JSON object with an "outbounds" array.
        (self.tree / "p0-only" / "bundle.json").write_text(
            "[1, 2, 3]", encoding="utf-8"
        )
        with self.assertRaisesRegex(ValueError, "p0-only.*bundle.json"):
            fixtures.validate_fixtures(self.tree, self.sha)

    def test_expected_profiles_must_be_an_array(self) -> None:
        (self.tree / "p0-only" / "expected-profiles.json").write_text(
            '{"not": "an array"}', encoding="utf-8"
        )
        with self.assertRaisesRegex(ValueError, "expected-profiles.json"):
            fixtures.validate_fixtures(self.tree, self.sha)

    def test_production_token_shape_is_detected(self) -> None:
        # A real-looking (non-frozen, non-fixture) UUID must be flagged.
        bundle_path = self.tree / "p0-only" / "bundle.json"
        bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
        bundle["outbounds"][0]["uuid"] = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
        bundle_path.write_text(json.dumps(bundle), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "production-token"):
            fixtures.validate_fixtures(self.tree, self.sha)

    def test_frozen_zero_uuid_is_not_flagged(self) -> None:
        # The all-zero / -fixture frozen shapes must not trip the guard.
        summary = fixtures.validate_fixtures(self.tree, self.sha)
        self.assertEqual(sorted(fixtures.REQUIRED_SCENARIOS), summary["scenarios"])

    def test_missing_scenario_directory_is_rejected(self) -> None:
        import shutil

        shutil.rmtree(self.tree / "bootstrap-bundle")
        with self.assertRaisesRegex(ValueError, "bootstrap-bundle"):
            fixtures.validate_fixtures(self.tree, self.sha)


if __name__ == "__main__":
    unittest.main()
