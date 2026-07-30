#!/usr/bin/env python3
"""Unit tests for the emulator evidence workload hook.

Instrumentation needs a live emulator and runs in CI. What is tested here is
everything that decides *what the oracle will later be asked to prove*: marker
derivation, the fixture identity digest, and the scenario plan the hook emits --
including that a gate whose instrumentation did not pass produces no window.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKLOAD = ROOT / "test-lab/scripts/network-evidence-emulator-workload.py"
ORACLE = ROOT / "test-lab/scripts/network-evidence-pcap-oracle.py"
REGISTRY = ROOT / "quality/release-gates/android-network-evidence-actions.json"


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    # The oracle declares dataclasses, which resolve their own module out of
    # sys.modules during class creation, so register before executing.
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


workload = load("emulator_workload", WORKLOAD)
oracle = load("pcap_oracle", ORACLE)


def fixture_manifest() -> dict:
    return {field: f"value-{field}" for field in workload.FIXTURE_IDENTITY_FIELDS}


class MarkerDerivationTest(unittest.TestCase):
    def test_markers_match_the_oracle_preimage(self) -> None:
        correlation = "a" * 64
        for gate_id, kind in (("dns-virtual-vpn-resolver", "dns"),):
            for phase in ("action", "outcome"):
                with self.subTest(gate_id=gate_id, phase=phase):
                    self.assertEqual(
                        workload.marker_sha256(correlation, gate_id, kind, phase),
                        hashlib.sha256(
                            oracle.derive_marker_preimage(
                                correlation, gate_id, kind, phase
                            )
                        ).hexdigest(),
                    )

    def test_action_and_outcome_markers_differ(self) -> None:
        correlation = "b" * 64
        self.assertNotEqual(
            workload.marker_sha256(correlation, "dns-core-crash-behavior", "dns", "action"),
            workload.marker_sha256(correlation, "dns-core-crash-behavior", "dns", "outcome"),
        )


class FixtureIdentityTest(unittest.TestCase):
    def test_identity_digest_matches_the_oracle_exactly(self) -> None:
        manifest = fixture_manifest()
        self.assertEqual(
            workload.fixture_identity_sha256(manifest),
            oracle._fixture_identity_sha256(manifest),
        )

    def test_identity_changes_when_any_bound_field_changes(self) -> None:
        base = workload.fixture_identity_sha256(fixture_manifest())
        for field in workload.FIXTURE_IDENTITY_FIELDS:
            with self.subTest(field=field):
                mutated = fixture_manifest()
                mutated[field] = "different"
                self.assertNotEqual(workload.fixture_identity_sha256(mutated), base)

    def test_missing_field_fails_closed(self) -> None:
        manifest = fixture_manifest()
        del manifest["fixtureDomain"]
        with self.assertRaisesRegex(workload.WorkloadError, "missing fields"):
            workload.fixture_identity_sha256(manifest)


class RegistryBindingTest(unittest.TestCase):
    def test_registry_env_is_required(self) -> None:
        os.environ.pop("RIPDPI_EVIDENCE_ACTION_REGISTRY", None)
        with self.assertRaisesRegex(workload.WorkloadError, "action registry path"):
            workload.load_registry()

    def test_registry_is_keyed_by_gate_and_covers_the_shipped_actions(self) -> None:
        os.environ["RIPDPI_EVIDENCE_ACTION_REGISTRY"] = str(REGISTRY)
        try:
            registry = workload.load_registry()
        finally:
            os.environ.pop("RIPDPI_EVIDENCE_ACTION_REGISTRY", None)
        shipped = json.loads(REGISTRY.read_text(encoding="utf-8"))["actions"]
        self.assertEqual(set(registry), {action["gateId"] for action in shipped})
        for gate_id, descriptor in registry.items():
            self.assertEqual(
                descriptor["receiptFile"],
                f"network-evidence-action-receipt-{gate_id}.json",
            )


class PlanEmissionTest(unittest.TestCase):
    """run_gate is driven against a stub adb so the plan shape is verifiable."""

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.shared = Path(self.temp.name)
        self.descriptor = {
            "gateId": "dns-virtual-vpn-resolver",
            "kind": "dns",
            "selector": "com.poyka.ripdpi.e2e.DnsNetworkEvidenceE2ETest#virtualVpnResolverUsesTunnelledResolver",
            "semanticRule": "virtual-vpn-resolver-v1",
            "receiptFile": "network-evidence-action-receipt-dns-virtual-vpn-resolver.json",
        }

    def tearDown(self) -> None:
        self.temp.cleanup()

    class StubDevice:
        def __init__(self, *, output: str, pulled: bool) -> None:
            self.output = output
            self.pulled = pulled

        def run(self, *args, timeout=0):
            class Result:
                returncode = 0

            Result.stdout = self.output.encode()
            return Result()

        def pull_app_file(self, name: str, destination: Path) -> bool:
            if not self.pulled:
                return False
            destination.write_text(json.dumps({"gateId": "x"}), encoding="utf-8")
            return True

    def call(self, device) -> dict | None:
        return workload.run_gate(
            device,
            self.shared,
            gate_id="dns-virtual-vpn-resolver",
            descriptor=self.descriptor,
            correlation_id="a" * 64,
            source_sha="b" * 40,
            client_sha="c" * 64,
            test_sha="d" * 64,
            fixture_identity="e" * 64,
        )

    def test_passing_gate_yields_a_window_with_oracle_shaped_fields(self) -> None:
        window = self.call(self.StubDevice(output="OK (1 test)", pulled=True))
        self.assertIsNotNone(window)
        self.assertEqual(set(window), oracle.WINDOW_FIELDS)
        self.assertEqual(window["id"], "dns-virtual-vpn-resolver")
        self.assertEqual(window["kind"], "dns")
        self.assertGreater(window["finishedAtEpoch"], window["startedAtEpoch"])
        self.assertEqual(
            window["actionMarkerSha256"],
            workload.marker_sha256("a" * 64, "dns-virtual-vpn-resolver", "dns", "action"),
        )

    def test_failing_instrumentation_produces_no_window(self) -> None:
        device = self.StubDevice(output="FAILURES!!!\nTests run: 1, Failures: 1", pulled=True)
        self.assertIsNone(self.call(device))
        self.assertTrue((self.shared / "dns-virtual-vpn-resolver-instrumentation.log").exists())

    def test_passing_gate_without_a_receipt_fails_closed(self) -> None:
        device = self.StubDevice(output="OK (1 test)", pulled=False)
        with self.assertRaisesRegex(workload.WorkloadError, "wrote no action receipt"):
            self.call(device)

    def test_clear_package_data_is_never_passed(self) -> None:
        """It would wipe the private storage holding the receipt we pull."""
        captured: list[tuple] = []

        class RecordingDevice(self.StubDevice):
            def run(inner, *args, timeout=0):  # noqa: N805
                captured.append(args)
                return super().run(*args, timeout=timeout)

        self.call(RecordingDevice(output="OK (1 test)", pulled=True))
        self.assertTrue(captured)
        self.assertNotIn(
            "clearPackageData", " ".join(part for args in captured for part in args)
        )


if __name__ == "__main__":
    unittest.main()
