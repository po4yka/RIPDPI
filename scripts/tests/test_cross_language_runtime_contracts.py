#!/usr/bin/env python3
"""Unit tests for scripts/ci/check_cross_language_runtime_contracts.py."""
from __future__ import annotations

import copy
import importlib.util
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


contracts = load_module(
    "check_cross_language_runtime_contracts",
    "scripts/ci/check_cross_language_runtime_contracts.py",
)


class CrossLanguageRuntimeContractsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.policy = contracts.load_json()

    def test_repo_policy_is_valid(self) -> None:
        summary = contracts.validate_policy(self.policy)
        self.assertEqual("cross_language_runtime_contracts_v1", summary["version"])
        self.assertEqual(len(contracts.REQUIRED_SURFACES), summary["surfaceCount"])
        self.assertGreaterEqual(summary["gateCount"], 17)
        surface_ids = {surface["id"] for surface in summary["surfaces"]}
        self.assertEqual(contracts.REQUIRED_SURFACES, surface_ids)

    def test_wrong_version_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["version"] = "cross_language_runtime_contracts_v0"
        with self.assertRaisesRegex(ValueError, "unexpected policy version"):
            contracts.validate_policy(broken)

    def test_missing_required_surface_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["surfaces"] = [surface for surface in broken["surfaces"] if surface["id"] != "native-jni-behavior"]
        with self.assertRaisesRegex(ValueError, "missing required surfaces"):
            contracts.validate_policy(broken)

    def test_duplicate_surface_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["surfaces"].append(copy.deepcopy(broken["surfaces"][0]))
        with self.assertRaisesRegex(ValueError, "duplicate surface id"):
            contracts.validate_policy(broken)

    def test_missing_required_gate_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        surface = next(surface for surface in broken["surfaces"] if surface["id"] == "diagnostics-json-wire")
        surface["gateTests"] = [gate for gate in surface["gateTests"] if gate["id"] != "rust-diagnostics-contract-fixtures"]
        with self.assertRaisesRegex(ValueError, "missing required gate ids"):
            contracts.validate_policy(broken)

    def test_missing_fixture_path_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        surface = next(surface for surface in broken["surfaces"] if surface["id"] == "native-config-json")
        surface["fixturePaths"].append("core/engine/src/test/resources/fixtures/not-a-real-fixture.json")
        with self.assertRaisesRegex(ValueError, "references missing fixturePaths path"):
            contracts.validate_policy(broken)

    def test_missing_trigger_term_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        surface = next(surface for surface in broken["surfaces"] if surface["id"] == "app-settings-protobuf")
        surface["changeTriggers"] = ["AppSettings protobuf migration"]
        with self.assertRaisesRegex(ValueError, "changeTriggers must mention"):
            contracts.validate_policy(broken)

    def test_missing_source_hint_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        surface = next(surface for surface in broken["surfaces"] if surface["id"] == "runtime-telemetry-json")
        surface["sourcePaths"] = [path for path in surface["sourcePaths"] if "NativeRuntimeSnapshot.kt" not in path]
        with self.assertRaisesRegex(ValueError, "sourcePaths are missing expected hints"):
            contracts.validate_policy(broken)

    def test_native_config_requires_live_tunnel_jni_sources_and_gates(self) -> None:
        for source_hint in ("config/payload.rs", "config/validation.rs", "session/jni_tests.rs"):
            broken = copy.deepcopy(self.policy)
            surface = next(surface for surface in broken["surfaces"] if surface["id"] == "native-config-json")
            surface["sourcePaths"] = [path for path in surface["sourcePaths"] if source_hint not in path]
            with self.assertRaisesRegex(ValueError, "sourcePaths are missing expected hints"):
                contracts.validate_policy(broken)

        for gate_id in ("rust-tunnel-android-config-schema", "rust-tunnel-android-jni-schema"):
            broken = copy.deepcopy(self.policy)
            surface = next(surface for surface in broken["surfaces"] if surface["id"] == "native-config-json")
            surface["gateTests"] = [gate for gate in surface["gateTests"] if gate["id"] != gate_id]
            with self.assertRaisesRegex(ValueError, "missing required gate ids"):
                contracts.validate_policy(broken)

    def test_native_config_distinguishes_jni_json_v3_from_standalone_yaml_v2(self) -> None:
        broken = copy.deepcopy(self.policy)
        surface = next(surface for surface in broken["surfaces"] if surface["id"] == "native-config-json")
        surface["invariants"] = ["Generic config invariants remain stable.", "Unknown fields remain compatible."]

        with self.assertRaisesRegex(ValueError, "invariants must mention"):
            contracts.validate_policy(broken)

    def test_main_validates_repo_policy(self) -> None:
        self.assertEqual(0, contracts.main([]))

    def test_main_report_mode(self) -> None:
        self.assertEqual(0, contracts.main(["--report"]))


if __name__ == "__main__":
    unittest.main()
