#!/usr/bin/env python3
"""Tests for the dual-vantage network evidence contract."""

from __future__ import annotations

import copy
import contextlib
import importlib.util
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, ValidationError


ROOT = Path(__file__).resolve().parents[2]
LEAK_CORPUS_PATH = (
    ROOT / "scripts/tests/fixtures/network_evidence_manifest/sensitive-leak-corpus.json"
)
MANIFEST_CLI_PATH = ROOT / "scripts/ci/network_evidence_manifest.py"
EVIDENCE_RUNNER_PATH = ROOT / "test-lab/scripts/run-dual-vantage-network-evidence.sh"
LEAKING_COLLECTOR_PATH = (
    ROOT / "scripts/tests/fixtures/network_evidence_manifest/leaking-fake-collector.py"
)
FAKE_WORKLOAD_PATH = (
    ROOT / "test-lab/scripts/fixtures/network-evidence-fake-workload.py"
)
PUBLICATION_FILENAMES = {
    "client-observation.json",
    "observer-observation.json",
    "manifest.json",
    "results.json",
}
STALE_OUTPUT_MARKER = b"SYNTHETIC_STALE_NETWORK_EVIDENCE_OUTPUT"


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
            "networkIdSha256": ("3" if role == "client-underlay" else "4") * 64,
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

    def load_leak_cases(self) -> list[dict]:
        corpus = json.loads(LEAK_CORPUS_PATH.read_text(encoding="utf-8"))
        self.assertEqual(corpus["version"], "network_evidence_leak_corpus_v1")
        case_ids = [case["id"] for case in corpus["cases"]]
        self.assertEqual(len(case_ids), len(set(case_ids)))
        self.assertEqual(
            set(case_ids),
            {
                "credentials",
                "password",
                "token",
                "private_key",
                "pre_shared_key",
                "authorization_header",
                "raw_device_id",
                "raw_device_serial",
                "full_client_ipv4",
                "full_client_ipv6",
                "client_mac",
                "sensitive_payload",
                "sensitive_body",
            },
        )
        return corpus["cases"]

    def run_manifest_cli(
        self, arguments: list[str]
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(MANIFEST_CLI_PATH), *arguments],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=15,
            check=False,
        )

    def assemble_cli_arguments(
        self, paths: dict[str, Path], output_path: Path
    ) -> list[str]:
        return [
            "assemble",
            "--client",
            str(paths["client"]),
            "--observer",
            str(paths["observer"]),
            "--source-sha",
            self.source_sha,
            "--applies-to",
            "android-client-release",
            "--generated-at-epoch",
            str(self.finished_at + 3),
            "--workflow-run-id",
            "42",
            "--workflow-run-attempt",
            "1",
            "--workload-sha256",
            "9" * 64,
            "--client-artifact-sha256",
            "8" * 64,
            "--output",
            str(output_path),
        ]

    def validate_cli_arguments(
        self, paths: dict[str, Path], output_path: Path
    ) -> list[str]:
        return [
            "validate",
            "--manifest",
            str(paths["manifest"]),
            "--artifact-root",
            str(paths["manifest"].parent),
            "--expected-source-sha",
            self.source_sha,
            "--applies-to",
            "android-client-release",
            "--current-epoch",
            str(self.finished_at + 4),
            "--max-age-seconds",
            "300",
            "--results-output",
            str(output_path),
        ]

    def stamp_cli_arguments(
        self, input_path: Path, output_path: Path, *, role: str
    ) -> list[str]:
        return [
            "stamp-observation",
            "--input",
            str(input_path),
            "--output",
            str(output_path),
            "--role",
            role,
            "--source-sha",
            self.source_sha,
            "--correlation-id",
            self.correlation_id,
            "--vantage-id-sha256",
            "5" * 64,
            "--network-id-sha256",
            "7" * 64,
            "--collector-sha256",
            "6" * 64,
        ]

    def write_cli_inputs(self, input_dir: Path) -> dict[str, Path]:
        input_dir.mkdir(parents=True)
        client_path = input_dir / "client-observation.json"
        observer_path = input_dir / "observer-observation.json"
        manifest_path = input_dir / "manifest.json"
        evidence.write_canonical_json(client_path, self.observation("client-underlay"))
        evidence.write_canonical_json(
            observer_path, self.observation("external-observer")
        )
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
        evidence.write_canonical_json(manifest_path, manifest)
        return {
            "client": client_path,
            "observer": observer_path,
            "manifest": manifest_path,
        }

    def assert_publication_empty(
        self, publication_dir: Path, *, marker: bytes | None = None
    ) -> None:
        self.assertTrue(publication_dir.is_dir())
        published_files = sorted(
            path for path in publication_dir.rglob("*") if path.is_file()
        )
        if marker is not None:
            published_bytes = b"".join(path.read_bytes() for path in published_files)
            self.assertNotIn(marker, published_bytes)
        self.assertEqual(published_files, [])
        for filename in PUBLICATION_FILENAMES:
            self.assertFalse((publication_dir / filename).exists())

    def run_dual_vantage_runner(
        self, *, case: dict, leak_role: str
    ) -> tuple[subprocess.CompletedProcess[str], Path]:
        case_root = self.root / "runner" / f"{case['id']}-{leak_role}"
        case_root.mkdir(parents=True)
        client_hook = case_root / "client-collector.py"
        observer_hook = case_root / "observer-collector.py"
        workload_hook = case_root / "workload.py"
        for destination, source in (
            (client_hook, LEAKING_COLLECTOR_PATH),
            (observer_hook, LEAKING_COLLECTOR_PATH),
            (workload_hook, FAKE_WORKLOAD_PATH),
        ):
            shutil.copyfile(source, destination)
            destination.chmod(0o700)

        config_path = case_root / "runner.json"
        config_path.write_text(
            json.dumps(
                {
                    "version": "ripdpi_network_evidence_runner_v1",
                    "clientHook": str(client_hook),
                    "observerHook": str(observer_hook),
                    "workloadHook": str(workload_hook),
                    "clientVantageId": "1" * 64,
                    "observerVantageId": "2" * 64,
                    "clientNetworkId": "3" * 64,
                    "observerNetworkId": "4" * 64,
                }
            ),
            encoding="utf-8",
        )
        config_path.chmod(0o600)
        publication_dir = case_root / "publication"
        publication_dir.mkdir()
        runner_temp = case_root / "runner-temp"
        runner_temp.mkdir()

        environment = os.environ.copy()
        environment.update(
            {
                "GITHUB_RUN_ID": "42",
                "GITHUB_RUN_ATTEMPT": "1",
                "RIPDPI_TEST_REPO_ROOT": str(ROOT),
                "RIPDPI_TEST_LEAK_CASE": json.dumps(
                    {"field": case["field"], "value": case["value"]},
                    separators=(",", ":"),
                ),
                "RIPDPI_TEST_LEAK_ROLE": leak_role,
                "RUNNER_TEMP": str(runner_temp),
            }
        )
        result = subprocess.run(
            [
                "/bin/bash",
                str(EVIDENCE_RUNNER_PATH),
                "--config",
                str(config_path),
                "--output-dir",
                str(publication_dir),
                "--source-sha",
                self.source_sha,
                "--client-artifact-sha256",
                "8" * 64,
            ],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
            timeout=15,
            check=False,
        )
        return result, publication_dir

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

    def test_repo_schema_validates_emitted_manifest_and_observations(self) -> None:
        schema = json.loads(
            (
                ROOT / "quality/release-gates/network-evidence-manifest-v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        Draft202012Validator.check_schema(schema)
        manifest = self.assemble()

        Draft202012Validator(schema).validate(manifest)
        observation_schema = {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "$ref": "#/$defs/observation",
            "$defs": schema["$defs"],
        }
        observation_validator = Draft202012Validator(observation_schema)
        for path in evidence.ROLE_PATHS.values():
            observation_validator.validate(
                json.loads((self.root / path).read_text(encoding="utf-8"))
            )

        invalid_observation = json.loads(
            (self.root / evidence.ROLE_PATHS["client-underlay"]).read_text(
                encoding="utf-8"
            )
        )
        invalid_observation.pop("networkIdSha256")
        with self.assertRaises(ValidationError):
            observation_validator.validate(invalid_observation)

    def test_missing_network_identity_is_rejected(self) -> None:
        observer = self.observation("external-observer")
        observer.pop("networkIdSha256")
        with self.assertRaisesRegex(ValueError, "networkIdSha256"):
            self.assemble(observer=observer)

    def test_duplicate_network_identity_is_rejected(self) -> None:
        observer = self.observation("external-observer")
        observer["networkIdSha256"] = self.observation("client-underlay")[
            "networkIdSha256"
        ]
        with self.assertRaisesRegex(ValueError, "network identities must differ"):
            self.assemble(observer=observer)

    def test_manifest_network_identity_mismatch_is_rejected(self) -> None:
        manifest = self.assemble()
        manifest["artifacts"][0]["networkIdSha256"] = "7" * 64
        with self.assertRaisesRegex(ValueError, "network identity digest mismatch"):
            self.validate(manifest)

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
        unstamped.pop("networkIdSha256")
        unstamped.pop("collectorSha256")

        stamped = evidence.stamp_observation(
            unstamped,
            expected_role="client-underlay",
            expected_source_sha=self.source_sha,
            expected_correlation_id=self.correlation_id,
            vantage_id_sha256="5" * 64,
            network_id_sha256="7" * 64,
            collector_sha256="6" * 64,
        )

        self.assertEqual(stamped["vantageIdSha256"], "5" * 64)
        self.assertEqual(stamped["networkIdSha256"], "7" * 64)
        self.assertEqual(stamped["collectorSha256"], "6" * 64)

    def test_runner_rejects_raw_network_identity_from_collector(self) -> None:
        unstamped = self.observation("client-underlay")
        unstamped.pop("vantageIdSha256")
        unstamped.pop("networkIdSha256")
        unstamped.pop("collectorSha256")
        unstamped["networkId"] = "private-network-identity"

        with self.assertRaisesRegex(ValueError, "unknown fields: networkId"):
            evidence.stamp_observation(
                unstamped,
                expected_role="client-underlay",
                expected_source_sha=self.source_sha,
                expected_correlation_id=self.correlation_id,
                vantage_id_sha256="5" * 64,
                network_id_sha256="7" * 64,
                collector_sha256="6" * 64,
            )

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

    def test_sensitive_leak_corpus_is_rejected_before_manifest_publication(
        self,
    ) -> None:
        for case in self.load_leak_cases():
            for leak_role in ("client-underlay", "external-observer"):
                with self.subTest(case=case["id"], role=leak_role):
                    marker = case["marker"].encode("utf-8")
                    leaked_value = evidence.canonical_json_bytes(case["value"])
                    self.assertIn(marker, leaked_value)

                    result, publication_dir = self.run_dual_vantage_runner(
                        case=case, leak_role=leak_role
                    )

                    self.assertNotEqual(
                        result.returncode, 0, result.stdout + result.stderr
                    )
                    self.assertIn(f"unknown fields: {case['field']}", result.stderr)
                    self.assert_publication_empty(publication_dir, marker=marker)

    def test_cli_rejects_malformed_and_wrong_versions_without_publication(self) -> None:
        cases = (
            ("malformed_manifest", "manifest", "malformed", "Expecting value"),
            (
                "wrong_manifest_version",
                "manifest",
                "wrong_version",
                "unexpected manifest version",
            ),
            (
                "malformed_client_observation",
                "client",
                "malformed",
                "Expecting value",
            ),
            (
                "wrong_client_observation_version",
                "client",
                "wrong_version",
                "unexpected observation version",
            ),
            (
                "malformed_observer_observation",
                "observer",
                "malformed",
                "Expecting value",
            ),
            (
                "wrong_observer_observation_version",
                "observer",
                "wrong_version",
                "unexpected observation version",
            ),
        )
        for case_id, document, mutation, expected_error in cases:
            with self.subTest(case=case_id):
                case_root = self.root / "cli" / case_id
                paths = self.write_cli_inputs(case_root / "input")
                publication_dir = case_root / "publication"
                publication_dir.mkdir()
                target_path = paths[document]
                if mutation == "malformed":
                    target_path.write_text('{"version":', encoding="utf-8")
                else:
                    value = json.loads(target_path.read_text(encoding="utf-8"))
                    if document == "manifest":
                        value["version"] = "network_evidence_manifest_v999"
                    else:
                        value["version"] = "network_evidence_observation_v999"
                    evidence.write_canonical_json(target_path, value)

                if document == "manifest":
                    output_path = publication_dir / "results.json"
                    output_path.write_bytes(STALE_OUTPUT_MARKER)
                    result = self.run_manifest_cli(
                        self.validate_cli_arguments(paths, output_path)
                    )
                else:
                    output_path = publication_dir / "manifest.json"
                    output_path.write_bytes(STALE_OUTPUT_MARKER)
                    result = self.run_manifest_cli(
                        self.assemble_cli_arguments(paths, output_path)
                    )

                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn("network evidence validation failed", result.stderr)
                self.assertIn(expected_error, result.stderr)
                self.assert_publication_empty(
                    publication_dir, marker=STALE_OUTPUT_MARKER
                )

        for alias_kind in ("symlink", "hardlink"):
            with self.subTest(alias=alias_kind):
                case_root = self.root / "aliases" / alias_kind
                paths = self.write_cli_inputs(case_root / "input")
                publication_dir = case_root / "publication"
                publication_dir.mkdir()
                output_path = publication_dir / "manifest.json"
                if alias_kind == "symlink":
                    output_path.symlink_to(paths["client"])
                else:
                    os.link(paths["client"], output_path)

                result = self.run_manifest_cli(
                    self.assemble_cli_arguments(paths, output_path)
                )

                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertTrue(paths["client"].is_file())
                self.assertTrue(output_path.exists() or output_path.is_symlink())
                self.assertTrue(os.path.samefile(paths["client"], output_path))

        original_policy_path = evidence.POLICY_PATH
        original_policy_bytes = original_policy_path.read_bytes()
        for command in ("assemble", "stamp-observation", "validate"):
            with self.subTest(implicit_input="policy", command=command):
                case_root = self.root / "policy-alias" / command
                paths = self.write_cli_inputs(case_root / "input")
                policy_copy = case_root / "policy.json"
                policy_copy.write_bytes(original_policy_bytes)
                if command == "assemble":
                    arguments = self.assemble_cli_arguments(paths, policy_copy)
                elif command == "validate":
                    arguments = self.validate_cli_arguments(paths, policy_copy)
                else:
                    unstamped = self.observation("client-underlay")
                    unstamped.pop("vantageIdSha256")
                    unstamped.pop("networkIdSha256")
                    unstamped.pop("collectorSha256")
                    unstamped_path = case_root / "unstamped-observation.json"
                    evidence.write_canonical_json(unstamped_path, unstamped)
                    arguments = self.stamp_cli_arguments(
                        unstamped_path, policy_copy, role="client-underlay"
                    )

                stderr = io.StringIO()
                evidence.POLICY_PATH = policy_copy
                try:
                    with contextlib.redirect_stderr(stderr):
                        status = evidence.main(arguments)
                finally:
                    evidence.POLICY_PATH = original_policy_path

                self.assertEqual(status, 1)
                self.assertIn("output path must differ", stderr.getvalue())
                self.assertEqual(policy_copy.read_bytes(), original_policy_bytes)

        case_root = self.root / "aliases" / "case-variant"
        case_root.mkdir(parents=True)
        probe_path = case_root / "CaseSensitivityProbe"
        probe_path.write_text("probe", encoding="utf-8")
        probe_variant = case_root / "casesensitivityprobe"
        case_insensitive = probe_variant.exists() and os.path.samefile(
            probe_path, probe_variant
        )
        with self.subTest(alias="case-variant"):
            if not case_insensitive:
                self.skipTest("filesystem is case-sensitive")
            paths = self.write_cli_inputs(case_root / "input")
            mixed_case_input = paths["client"].with_name("Client-Observation.json")
            paths["client"].replace(mixed_case_input)
            paths["client"] = mixed_case_input
            output_path = mixed_case_input.with_name("CLIENT-OBSERVATION.JSON")

            result = self.run_manifest_cli(
                self.assemble_cli_arguments(paths, output_path)
            )

            self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
            self.assertTrue(mixed_case_input.is_file())
            self.assertTrue(output_path.is_file())
            self.assertTrue(os.path.samefile(mixed_case_input, output_path))

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
