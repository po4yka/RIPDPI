#!/usr/bin/env python3
"""Tests for the dual-vantage network evidence contract."""

from __future__ import annotations

import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def load_module():
    path = ROOT / "scripts/ci/network_evidence_manifest.py"
    spec = importlib.util.spec_from_file_location("network_evidence_manifest", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


evidence = load_module()


class NetworkEvidenceManifestTest(unittest.TestCase):
    source_sha = "a" * 40
    correlation_id = "b" * 64
    started_at = 2_000_000_000
    finished_at = started_at + 30

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.gate_ids = sorted(evidence.required_gate_ids())

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def kind_for(gate_id: str) -> str:
        if gate_id.startswith("dns-") or gate_id.startswith("synthetic-"):
            return "dns"
        if "ipv6" in gate_id or gate_id.startswith(("ipv4only-", "dualstack-")):
            return "ipv6"
        return "direct_window"

    def observation(self, role: str, *, correlation_id: str | None = None) -> dict:
        return {
            "version": "network_evidence_observation_v1",
            "sourceSha": self.source_sha,
            "correlationId": correlation_id or self.correlation_id,
            "role": role,
            "vantageIdSha256": ("c" if role == "client-underlay" else "d") * 64,
            "collectorSha256": ("e" if role == "client-underlay" else "f") * 64,
            "captureStartedAtEpoch": self.started_at - 2,
            "captureFinishedAtEpoch": self.finished_at + 2,
            "rawCaptureSha256": ("1" if role == "client-underlay" else "2") * 64,
            "windows": [
                {
                    "id": gate_id,
                    "kind": self.kind_for(gate_id),
                    "startedAtEpoch": self.started_at,
                    "finishedAtEpoch": self.finished_at,
                    "expectedPacketCount": 3,
                    "unexpectedPacketCount": 0,
                    "captureErrorCount": 0,
                }
                for gate_id in self.gate_ids
            ],
        }

    def write_observations(
        self, client: dict | None = None, observer: dict | None = None
    ):
        client_path = self.root / "client-observation.json"
        observer_path = self.root / "observer-observation.json"
        evidence.write_canonical_json(
            client_path, client or self.observation("client-underlay")
        )
        evidence.write_canonical_json(
            observer_path, observer or self.observation("external-observer")
        )
        return client_path, observer_path

    def assemble(
        self, client: dict | None = None, observer: dict | None = None
    ) -> dict:
        client_path, observer_path = self.write_observations(client, observer)
        manifest = evidence.assemble_manifest(
            client_path=client_path,
            observer_path=observer_path,
            source_sha=self.source_sha,
            applies_to="android-client-release",
            generated_at_epoch=self.finished_at + 3,
            workflow_path=evidence.EVIDENCE_WORKFLOW_PATH,
            workflow_run_id=42,
            workflow_run_attempt=1,
            workload_sha256="9" * 64,
            client_artifact_sha256="8" * 64,
        )
        evidence.write_canonical_json(self.root / "manifest.json", manifest)
        return manifest

    def validate(self, manifest: dict) -> dict:
        return evidence.validate_manifest(
            manifest,
            artifact_root=self.root,
            expected_source_sha=self.source_sha,
            applies_to="android-client-release",
            current_epoch=self.finished_at + 4,
            max_age_seconds=300,
        )

    def test_valid_dual_vantage_manifest_derives_all_pass(self) -> None:
        manifest = self.assemble()
        result = self.validate(manifest)

        self.assertEqual(
            result["gateResults"], {gate_id: "PASS" for gate_id in self.gate_ids}
        )
        self.assertEqual(
            [item["role"] for item in manifest["artifacts"]],
            ["client-underlay", "external-observer"],
        )

    def test_manifest_serialization_is_deterministic(self) -> None:
        first = evidence.canonical_json_bytes(self.assemble())
        second = evidence.canonical_json_bytes(self.assemble())
        self.assertEqual(first, second)

    def test_repo_schema_pins_manifest_and_observation_versions(self) -> None:
        schema = json.loads(
            (
                ROOT / "quality/release-gates/network-evidence-manifest-v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        self.assertEqual(
            schema["properties"]["version"]["const"], evidence.MANIFEST_VERSION
        )
        self.assertEqual(
            schema["$defs"]["observation"]["properties"]["version"]["const"],
            evidence.OBSERVATION_VERSION,
        )
        self.assertEqual(set(schema["required"]), evidence.MANIFEST_FIELDS)
        self.assertEqual(
            set(schema["$defs"]["provenance"]["required"]),
            evidence.PROVENANCE_FIELDS,
        )
        self.assertEqual(
            set(schema["$defs"]["artifact"]["required"]), evidence.ARTIFACT_FIELDS
        )
        self.assertEqual(
            set(schema["$defs"]["observation"]["required"]),
            evidence.OBSERVATION_FIELDS,
        )
        self.assertEqual(
            set(schema["$defs"]["window"]["required"]), evidence.WINDOW_FIELDS
        )
        self.assertEqual(
            set(schema["$defs"]["scenario"]["required"]),
            evidence.SCENARIO_FIELDS,
        )
        self.assertEqual(
            set(schema["properties"]["gateResults"]["additionalProperties"]["enum"]),
            {"PASS", "FAIL", "INCONCLUSIVE"},
        )

    def test_missing_vantage_is_rejected(self) -> None:
        manifest = self.assemble()
        manifest["artifacts"] = manifest["artifacts"][:1]
        with self.assertRaisesRegex(ValueError, "client and observer"):
            self.validate(manifest)

    def test_correlation_mismatch_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "correlationId"):
            self.assemble(
                observer=self.observation("external-observer", correlation_id="e" * 64)
            )

    def test_duplicate_vantage_identity_is_rejected(self) -> None:
        observer = self.observation("external-observer")
        observer["vantageIdSha256"] = self.observation("client-underlay")[
            "vantageIdSha256"
        ]
        with self.assertRaisesRegex(ValueError, "vantage identities must differ"):
            self.assemble(observer=observer)

    def test_runner_stamps_and_canonicalizes_hook_observation(self) -> None:
        unstamped = self.observation("client-underlay")
        unstamped.pop("vantageIdSha256")
        unstamped.pop("collectorSha256")

        stamped = evidence.stamp_observation(
            unstamped,
            expected_role="client-underlay",
            expected_source_sha=self.source_sha,
            expected_correlation_id=self.correlation_id,
            vantage_id_sha256="5" * 64,
            collector_sha256="6" * 64,
        )

        self.assertEqual(stamped["vantageIdSha256"], "5" * 64)
        self.assertEqual(stamped["collectorSha256"], "6" * 64)

    def test_missing_gate_window_is_rejected(self) -> None:
        observer = self.observation("external-observer")
        observer["windows"].pop()
        with self.assertRaisesRegex(ValueError, "window ids"):
            self.assemble(observer=observer)

    def test_non_overlapping_windows_are_rejected(self) -> None:
        observer = self.observation("external-observer")
        observer["windows"][0]["startedAtEpoch"] = self.finished_at + 1
        observer["windows"][0]["finishedAtEpoch"] = self.finished_at + 2
        with self.assertRaisesRegex(ValueError, "overlap"):
            self.assemble(observer=observer)

    def test_zero_positive_control_is_rejected(self) -> None:
        observer = self.observation("external-observer")
        observer["windows"][0]["expectedPacketCount"] = 0
        with self.assertRaisesRegex(ValueError, "positive control"):
            self.assemble(observer=observer)

    def test_unexpected_packets_derive_failure(self) -> None:
        observer = self.observation("external-observer")
        failed_gate = observer["windows"][0]["id"]
        observer["windows"][0]["unexpectedPacketCount"] = 1
        manifest = self.assemble(observer=observer)

        self.assertEqual(self.validate(manifest)["gateResults"][failed_gate], "FAIL")

    def test_capture_error_derives_inconclusive(self) -> None:
        client = self.observation("client-underlay")
        gate_id = client["windows"][0]["id"]
        client["windows"][0]["captureErrorCount"] = 1
        manifest = self.assemble(client=client)

        self.assertEqual(
            self.validate(manifest)["gateResults"][gate_id], "INCONCLUSIVE"
        )

    def test_noncanonical_observation_is_rejected(self) -> None:
        manifest = self.assemble()
        client_path = self.root / "client-observation.json"
        client = json.loads(client_path.read_text(encoding="utf-8"))
        client_path.write_text(json.dumps(client, indent=2), encoding="utf-8")
        manifest["artifacts"][0]["sha256"] = evidence.sha256_bytes(
            client_path.read_bytes()
        )

        with self.assertRaisesRegex(ValueError, "not canonical JSON"):
            self.validate(manifest)

    def test_digest_tampering_is_rejected(self) -> None:
        manifest = self.assemble()
        client_path = self.root / "client-observation.json"
        client = json.loads(client_path.read_text(encoding="utf-8"))
        client["windows"][0]["unexpectedPacketCount"] = 1
        evidence.write_canonical_json(client_path, client)

        with self.assertRaisesRegex(ValueError, "digest"):
            self.validate(manifest)

    def test_artifact_path_traversal_is_rejected(self) -> None:
        manifest = self.assemble()
        manifest["artifacts"][0]["path"] = "../client-observation.json"
        with self.assertRaisesRegex(ValueError, "artifact path"):
            self.validate(manifest)

    def test_selected_workflow_run_must_match_provenance(self) -> None:
        manifest = self.assemble()
        with self.assertRaisesRegex(ValueError, "workflowRunId"):
            evidence.validate_manifest(
                manifest,
                artifact_root=self.root,
                expected_source_sha=self.source_sha,
                applies_to="android-client-release",
                current_epoch=self.finished_at + 4,
                max_age_seconds=300,
                expected_workflow_run_id=43,
                expected_workflow_run_attempt=1,
            )

    def test_future_manifest_is_rejected(self) -> None:
        manifest = self.assemble()
        manifest["generatedAtEpoch"] = self.finished_at + 10_000
        with self.assertRaisesRegex(ValueError, "future"):
            self.validate(manifest)

    def test_stale_manifest_is_rejected(self) -> None:
        manifest = self.assemble()
        with self.assertRaisesRegex(ValueError, "stale"):
            evidence.validate_manifest(
                manifest,
                artifact_root=self.root,
                expected_source_sha=self.source_sha,
                applies_to="android-client-release",
                current_epoch=self.finished_at + 10_000,
                max_age_seconds=300,
            )

    def test_fresh_manifest_cannot_repackage_stale_observations(self) -> None:
        manifest = self.assemble()
        now = self.finished_at + 10_000
        manifest["generatedAtEpoch"] = now

        with self.assertRaisesRegex(ValueError, "observation capture is stale"):
            evidence.validate_manifest(
                manifest,
                artifact_root=self.root,
                expected_source_sha=self.source_sha,
                applies_to="android-client-release",
                current_epoch=now,
                max_age_seconds=300,
            )

    def test_unknown_or_sensitive_fields_are_rejected(self) -> None:
        client = self.observation("client-underlay")
        client["authToken"] = "do-not-publish"
        client_path, observer_path = self.write_observations(client=client)
        with self.assertRaisesRegex(ValueError, "unknown fields"):
            evidence.assemble_manifest(
                client_path=client_path,
                observer_path=observer_path,
                source_sha=self.source_sha,
                applies_to="android-client-release",
                generated_at_epoch=self.finished_at + 3,
                workflow_path=evidence.EVIDENCE_WORKFLOW_PATH,
                workflow_run_id=42,
                workflow_run_attempt=1,
                workload_sha256="9" * 64,
                client_artifact_sha256="8" * 64,
            )

    def test_manifest_tampering_with_derived_results_is_rejected(self) -> None:
        observer = self.observation("external-observer")
        observer["windows"][0]["unexpectedPacketCount"] = 1
        manifest = self.assemble(observer=observer)
        tampered = copy.deepcopy(manifest)
        tampered["gateResults"][observer["windows"][0]["id"]] = "PASS"
        with self.assertRaisesRegex(ValueError, "derived gateResults"):
            self.validate(tampered)


if __name__ == "__main__":
    unittest.main()
