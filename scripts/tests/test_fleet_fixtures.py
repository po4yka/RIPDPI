#!/usr/bin/env python3
"""Unit tests for scripts/ci/check_fleet_fixtures.py."""
from __future__ import annotations

import importlib.util
import json
import os
import re
import shutil
import subprocess
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

    def test_pinned_sha_is_a_real_commit_and_matches_all_metadata(self) -> None:
        sha = fixtures.pinned_sha()
        self.assertRegex(sha, re.compile(r"^[0-9a-f]{40}$"))
        self.assertNotEqual("0" * 40, sha)
        # The pin must be exactly what every committed meta.json carries.
        for scenario in fixtures.REQUIRED_SCENARIOS:
            meta = json.loads(
                (fixtures.FIXTURES_ROOT / scenario / "meta.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual(sha, meta["deployer_git_sha"])

    def test_every_expressible_scenario_has_emitted_provenance(self) -> None:
        self.assertEqual(
            set(fixtures.REQUIRED_SCENARIOS) - {"p2a-hysteria-port-hop"},
            set(fixtures.EMITTED_SCENARIOS),
        )

    def test_cross_repo_gate_requires_the_exact_deployer_checkout(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            env = os.environ.copy()
            env["RIPDPI_VPN_DEPLOY_DIR"] = str(Path(tmp) / "missing-deployer")
            result = subprocess.run(
                [str(fixtures.ROOT / "scripts/refresh-fleet-fixtures.sh"), "--check"],
                cwd=fixtures.ROOT,
                env=env,
                text=True,
                capture_output=True,
                check=False,
            )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("deployer", result.stderr.lower())

    def test_cross_repo_gate_emits_and_validates_a_bundle(self) -> None:
        source = (fixtures.ROOT / "scripts/refresh-fleet-fixtures.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("scripts/emit-bundle.sh", source)
        self.assertIn("scripts/validate-bundle.py", source)
        self.assertNotIn("emitter_available", source)

    def test_emitted_scenario_exit_two_is_a_hard_failure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            app = root / "app"
            deploy = root / "deploy"
            refresh = app / "scripts/refresh-fleet-fixtures.sh"
            refresh.parent.mkdir(parents=True)
            shutil.copy2(fixtures.ROOT / "scripts/refresh-fleet-fixtures.sh", refresh)

            frozen = app / "scripts/fleet-fixtures/frozen-secrets.yaml"
            frozen.parent.mkdir(parents=True)
            frozen.write_text("xray: {}\n", encoding="utf-8")
            pin_file = frozen.parent / "deployer-git-sha.txt"

            checker = app / "scripts/ci/check_fleet_fixtures.py"
            checker.parent.mkdir(parents=True)
            checker.write_text("print('fake structural check')\n", encoding="utf-8")

            fixture_root = app / "core/data/src/test/resources/fleet-fixtures"
            for scenario in fixtures.REQUIRED_SCENARIOS:
                scenario_dir = fixture_root / scenario
                scenario_dir.mkdir(parents=True)
                (scenario_dir / "meta.json").write_text("{}\n", encoding="utf-8")

            scripts = deploy / "scripts"
            scripts.mkdir(parents=True)
            emitter = scripts / "emit-bundle.sh"
            emitter.write_text("#!/usr/bin/env bash\nexit 2\n", encoding="utf-8")
            emitter.chmod(0o755)
            (scripts / "validate-bundle.py").write_text(
                "raise SystemExit('validator must not run')\n", encoding="utf-8"
            )
            subprocess.run(["git", "init", "-q", str(deploy)], check=True)
            subprocess.run(
                ["git", "-C", str(deploy), "config", "user.email", "test@example.invalid"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(deploy), "config", "user.name", "Fleet test"],
                check=True,
            )
            subprocess.run(["git", "-C", str(deploy), "add", "."], check=True)
            subprocess.run(
                ["git", "-C", str(deploy), "commit", "-qm", "fixture"], check=True
            )
            sha = subprocess.check_output(
                ["git", "-C", str(deploy), "rev-parse", "HEAD"], text=True
            ).strip()
            pin_file.write_text(sha + "\n", encoding="utf-8")

            env = os.environ.copy()
            env["RIPDPI_VPN_DEPLOY_DIR"] = str(deploy)
            result = subprocess.run(
                [str(refresh), "--check"],
                cwd=app,
                env=env,
                text=True,
                capture_output=True,
                check=False,
            )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("emit-bundle.sh failed for p0-only", result.stderr)

    def test_workflow_checks_out_the_pinned_deployer_before_the_gate(self) -> None:
        source = (fixtures.ROOT / ".github/workflows/fleet-fixtures.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn("repository: po4yka/ripdpi-vpn-deploy", source)
        self.assertIn("ref: ${{ steps.deployer-pin.outputs.sha }}", source)
        self.assertIn("scripts/refresh-fleet-fixtures.sh --write", source)
        self.assertIn(
            "git diff --exit-code -- core/data/src/test/resources/fleet-fixtures",
            source,
        )
        self.assertIn("RIPDPI_VPN_DEPLOY_DIR", source)
        self.assertIn("push:\n    branches: [main]", source)
        self.assertIn("actions/setup-java@be666c2fcd27ec809703dec50e508c2fdc7f6654", source)
        self.assertNotIn("uses: ./.github/actions/setup-android-rust", source)
        self.assertNotIn("actions/cache", source)
        self.assertLess(
            source.index("scripts/refresh-fleet-fixtures.sh --write"),
            source.index("git diff --exit-code"),
        )
        self.assertLess(
            source.index("git diff --exit-code"),
            source.index("./gradlew :core:data:testDebugUnitTest"),
        )


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

    def test_production_password_is_detected_in_parser_reference(self) -> None:
        bundle_path = self.tree / "p2a-hysteria-only" / "bundle.json"
        bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
        bundle["outbounds"][0]["password"] = "live-hysteria-password"
        bundle_path.write_text(json.dumps(bundle), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "production-token.*password"):
            fixtures.validate_fixtures(self.tree, self.sha)

    def test_non_documentation_server_ip_is_detected_in_parser_reference(self) -> None:
        bundle_path = self.tree / "p2a-hysteria-only" / "bundle.json"
        bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
        bundle["outbounds"][0]["server"] = "8.8.8.8"
        bundle_path.write_text(json.dumps(bundle), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "production-token.*server"):
            fixtures.validate_fixtures(self.tree, self.sha)

    def test_unmarked_public_key_is_detected_in_parser_reference(self) -> None:
        bundle_path = self.tree / "multi-host-failover" / "bundle.json"
        bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
        bundle["outbounds"][0]["tls"]["reality"]["public_key"] = (
            "wD3J4Yv4mA9y0nQb3P2L1K8jH7gF6dS5aZ4xC3vB2nM="
        )
        bundle_path.write_text(json.dumps(bundle), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "production-token.*public_key"):
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

    def test_placeholder_pin_is_rejected(self) -> None:
        pin_file = self.tmp / "deployer-git-sha.txt"
        pin_file.write_text("0" * 40 + "-fixture\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "40-character lowercase git SHA"):
            fixtures.pinned_sha(pin_file)

    def test_parser_reference_scenario_cannot_claim_emitted_provenance(self) -> None:
        meta_path = self.tree / "p2a-hysteria-port-hop" / "meta.json"
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        meta["provenance"] = "emitted"
        meta_path.write_text(json.dumps(meta), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "provenance"):
            fixtures.validate_fixtures(self.tree, self.sha)

    def test_parser_reference_requires_an_explicit_reason(self) -> None:
        meta_path = self.tree / "p2a-hysteria-port-hop" / "meta.json"
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        meta.pop("reference_reason", None)
        meta_path.write_text(json.dumps(meta), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "reference_reason"):
            fixtures.validate_fixtures(self.tree, self.sha)


if __name__ == "__main__":
    unittest.main()
