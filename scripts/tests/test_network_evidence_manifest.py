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
RUNNER_TEST_TIMEOUT_SECONDS = 90


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
        self.original_producer_policy_path = evidence.PRODUCER_POLICY_PATH
        evidence.PRODUCER_POLICY_PATH = self.root / "network-evidence-producers.json"
        evidence.write_canonical_json(
            evidence.PRODUCER_POLICY_PATH,
            {
                "version": "network_evidence_producers_v2",
                "clientCollectorSha256": ["e" * 64],
                "observerCollectorSha256": ["f" * 64],
                "workloadSha256": ["9" * 64],
                "clientArtifactSha256": ["8" * 64],
                "testArtifactSha256": ["7" * 64],
            },
        )
        self.gate_ids = sorted(evidence.required_gate_ids())

    def tearDown(self) -> None:
        evidence.PRODUCER_POLICY_PATH = self.original_producer_policy_path
        self.temp.cleanup()

    @staticmethod
    def kind_for(gate_id: str) -> str:
        if gate_id.startswith("dns-") or gate_id.startswith("synthetic-"):
            return "dns"
        if "ipv6" in gate_id or gate_id.startswith(("ipv4only-", "dualstack-")):
            return "ipv6"
        return "direct_window"

    def observation(self, role: str, *, correlation_id: str | None = None) -> dict:
        correlation = correlation_id or self.correlation_id
        plan = self.plan(correlation_id=correlation)
        return {
            "version": "network_evidence_observation_v3",
            "sourceSha": self.source_sha,
            "correlationId": correlation,
            "role": role,
            "vantageIdSha256": ("c" if role == "client-underlay" else "d") * 64,
            "networkIdSha256": ("3" if role == "client-underlay" else "4") * 64,
            "collectorSha256": ("e" if role == "client-underlay" else "f") * 64,
            "clientArtifactSha256": "8" * 64,
            "testArtifactSha256": "7" * 64,
            "scenarioPlanSha256": evidence.sha256_bytes(
                evidence.canonical_json_bytes(plan)
            ),
            "captureStartedAtEpoch": self.started_at - 2,
            "captureFinishedAtEpoch": self.finished_at + 2,
            "rawCaptureSha256": ("1" if role == "client-underlay" else "2") * 64,
            "windows": [
                {
                    **window,
                    "expectedPacketCount": 3,
                    "unexpectedPacketCount": 0,
                    "captureErrorCount": 0,
                    "actionObservedCount": 1,
                    "outcomeObservedCount": 1,
                }
                for window in plan["windows"]
            ],
        }

    def plan(self, *, correlation_id: str | None = None) -> dict:
        correlation = correlation_id or self.correlation_id
        return {
            "version": evidence.PLAN_VERSION,
            "sourceSha": self.source_sha,
            "correlationId": correlation,
            "clientArtifactSha256": "8" * 64,
            "testArtifactSha256": "7" * 64,
            "windows": [
                {
                    "id": gate_id,
                    "kind": self.kind_for(gate_id),
                    "startedAtEpoch": self.started_at,
                    "finishedAtEpoch": self.finished_at,
                    "actionMarkerSha256": evidence.derive_marker(
                        correlation, gate_id, self.kind_for(gate_id), "action"
                    ),
                    "outcomeMarkerSha256": evidence.derive_marker(
                        correlation, gate_id, self.kind_for(gate_id), "outcome"
                    ),
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
            execution_kind="local",
            execution_id="unit-test",
            execution_attempt=1,
            execution_definition="local",
            runner_sha256=evidence.sha256_bytes(evidence.RUNNER_PATH.read_bytes()),
            validator_sha256=evidence.sha256_bytes(
                Path(evidence.__file__).read_bytes()
            ),
            policy_sha256=evidence.sha256_bytes(evidence.POLICY_PATH.read_bytes()),
            producer_policy_sha256=evidence.sha256_bytes(
                evidence.PRODUCER_POLICY_PATH.read_bytes()
            ),
            workload_sha256="9" * 64,
            client_artifact_sha256="8" * 64,
            test_artifact_sha256="7" * 64,
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
        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            returncode = evidence.main(arguments)
        return subprocess.CompletedProcess(
            [sys.executable, str(MANIFEST_CLI_PATH), *arguments],
            returncode,
            stdout.getvalue(),
            stderr.getvalue(),
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
            "--execution-kind",
            "local",
            "--execution-id",
            "unit-test",
            "--execution-attempt",
            "1",
            "--execution-definition",
            "local",
            "--runner-sha256",
            evidence.sha256_bytes(evidence.RUNNER_PATH.read_bytes()),
            "--validator-sha256",
            evidence.sha256_bytes(Path(evidence.__file__).read_bytes()),
            "--policy-sha256",
            evidence.sha256_bytes(evidence.POLICY_PATH.read_bytes()),
            "--producer-policy-sha256",
            evidence.sha256_bytes(evidence.PRODUCER_POLICY_PATH.read_bytes()),
            "--workload-sha256",
            "9" * 64,
            "--client-artifact-sha256",
            "8" * 64,
            "--test-artifact-sha256",
            "7" * 64,
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
        plan_path = input_path.parent / "scenario-plan.json"
        evidence.write_canonical_json(plan_path, self.plan())
        return [
            "stamp-observation",
            "--input",
            str(input_path),
            "--plan",
            str(plan_path),
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
            "--client-artifact-sha256",
            "8" * 64,
            "--test-artifact-sha256",
            "7" * 64,
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
            execution_kind="local",
            execution_id="unit-test",
            execution_attempt=1,
            execution_definition="local",
            runner_sha256=evidence.sha256_bytes(evidence.RUNNER_PATH.read_bytes()),
            validator_sha256=evidence.sha256_bytes(
                Path(evidence.__file__).read_bytes()
            ),
            policy_sha256=evidence.sha256_bytes(evidence.POLICY_PATH.read_bytes()),
            producer_policy_sha256=evidence.sha256_bytes(
                evidence.PRODUCER_POLICY_PATH.read_bytes()
            ),
            workload_sha256="9" * 64,
            client_artifact_sha256="8" * 64,
            test_artifact_sha256="7" * 64,
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

    def prepare_runner_source(self) -> Path:
        source_checkout = self.root / "runner-source"
        if source_checkout.exists():
            return source_checkout
        staging_checkout = self.root / "runner-source.build"
        subprocess.run(
            ["git", "clone", "--quiet", "--shared", str(ROOT), str(staging_checkout)],
            check=True,
            timeout=30,
        )
        changed = subprocess.run(
            ["git", "-C", str(ROOT), "diff", "--name-only"],
            capture_output=True,
            check=True,
            text=True,
            timeout=10,
        ).stdout.splitlines()
        untracked = subprocess.run(
            ["git", "-C", str(ROOT), "ls-files", "--others", "--exclude-standard"],
            capture_output=True,
            check=True,
            text=True,
            timeout=10,
        ).stdout.splitlines()
        for relative in sorted(set(changed + untracked)):
            if not (ROOT / relative).exists():
                target = staging_checkout / relative
                if target.exists():
                    target.unlink()
                continue
            destination = staging_checkout / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, destination)
        producer_policy = {
            "version": "network_evidence_producers_v2",
            "clientCollectorSha256": [
                evidence.sha256_bytes(LEAKING_COLLECTOR_PATH.read_bytes())
            ],
            "observerCollectorSha256": [
                evidence.sha256_bytes(LEAKING_COLLECTOR_PATH.read_bytes())
            ],
            "workloadSha256": [evidence.sha256_bytes(FAKE_WORKLOAD_PATH.read_bytes())],
            "clientArtifactSha256": [
                evidence.sha256_bytes(b"synthetic client artifact\n")
            ],
            "testArtifactSha256": [evidence.sha256_bytes(b"synthetic test artifact\n")],
        }
        evidence.write_canonical_json(
            staging_checkout / "quality/release-gates/network-evidence-producers.json",
            producer_policy,
        )
        subprocess.run(
            ["git", "-C", str(staging_checkout), "add", "-A"],
            check=True,
            timeout=10,
        )
        subprocess.run(
            [
                "git",
                "-C",
                str(staging_checkout),
                "-c",
                "user.name=RIPDPI-Test",
                "-c",
                "user.email=test.invalid",
                "commit",
                "--quiet",
                "-m",
                "test fixture snapshot",
            ],
            check=True,
            timeout=20,
        )
        staging_checkout.rename(source_checkout)
        return source_checkout

    def run_dual_vantage_runner(
        self, *, case: dict, leak_role: str
    ) -> tuple[subprocess.CompletedProcess[str], Path]:
        source_checkout = self.root / "runner-source"
        if not source_checkout.is_dir():
            raise AssertionError("runner source fixture was not prepared")
        runner_source_sha = subprocess.run(
            ["git", "-C", str(source_checkout), "rev-parse", "HEAD"],
            capture_output=True,
            check=True,
            text=True,
            timeout=5,
        ).stdout.strip()
        client_artifact = self.root / "runner-client.apk"
        client_artifact.write_bytes(b"synthetic client artifact\n")
        test_artifact = self.root / "runner-test.apk"
        test_artifact.write_bytes(b"synthetic test artifact\n")
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
        fake_bin = self.root / "fake-bin"
        fake_bin.mkdir(exist_ok=True)
        fake_adb = fake_bin / "adb"
        fake_adb.write_text(
            """#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == devices ]]; then
  printf 'List of devices attached\\nphysical-test\\tdevice\\n'
  exit 0
fi
shift 2
if [[ "${1:-}" == shell && "${2:-}" == getprop && "${3:-}" == ro.kernel.qemu ]]; then
  printf '0\\n'
elif [[ "${1:-}" == shell && "${2:-}" == getprop && "${3:-}" == ro.product.name ]]; then
  printf 'pixel_physical\\n'
elif [[ "${1:-}" == shell && "${2:-}" == pm && "${3:-}" == path ]]; then
  if [[ "${4:-}" == com.poyka.ripdpi.test ]]; then
    printf 'package:/data/app/ripdpi-test/base.apk\\n'
  else
    printf 'package:/data/app/ripdpi/base.apk\\n'
  fi
elif [[ "${1:-}" == pull ]]; then
  if [[ "$2" == */ripdpi-test/* ]]; then
    cp "$RIPDPI_TEST_INSTALLED_TEST_APK" "$3"
  else
    cp "$RIPDPI_TEST_INSTALLED_APK" "$3"
  fi
else
  exit 2
fi
""",
            encoding="utf-8",
        )
        fake_adb.chmod(0o700)

        environment = os.environ.copy()
        environment.update(
            {
                "RIPDPI_TEST_REPO_ROOT": str(source_checkout),
                "RIPDPI_TEST_INSTALLED_APK": str(client_artifact),
                "RIPDPI_TEST_INSTALLED_TEST_APK": str(test_artifact),
                "RIPDPI_TEST_LEAK_CASE": json.dumps(
                    {"field": case["field"], "value": case["value"]},
                    separators=(",", ":"),
                ),
                "RIPDPI_TEST_LEAK_ROLE": leak_role,
                "RUNNER_TEMP": str(runner_temp),
                "PATH": f"{fake_bin}:{environment['PATH']}",
            }
        )
        result = subprocess.run(
            [
                "/bin/bash",
                str(
                    source_checkout
                    / "test-lab/scripts/run-dual-vantage-network-evidence.sh"
                ),
                "--config",
                str(config_path),
                "--output-dir",
                str(publication_dir),
                "--source-sha",
                runner_source_sha,
                "--source-root",
                str(source_checkout),
                "--client-artifact",
                str(client_artifact),
                "--test-artifact",
                str(test_artifact),
                "--execution-kind",
                "local",
                "--execution-id",
                "leak-test",
                "--execution-attempt",
                "1",
            ],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
            timeout=RUNNER_TEST_TIMEOUT_SECONDS,
            check=False,
        )
        return result, publication_dir

    def test_valid_dual_vantage_manifest_derives_all_pass(self) -> None:
        manifest = self.assemble()
        result = self.validate(manifest)

        self.assertEqual(len(self.gate_ids), 10)
        self.assertIn("dns-virtual-vpn-resolver", self.gate_ids)
        self.assertIn("killswitch-tun-establish-native-ready", self.gate_ids)
        self.assertEqual(
            result["gateResults"], {gate_id: "PASS" for gate_id in self.gate_ids}
        )
        self.assertEqual(
            manifest["provenance"],
            {
                "executionKind": "local",
                "executionId": "unit-test",
                "executionAttempt": 1,
                "executionDefinition": "local",
                "runnerSha256": evidence.sha256_bytes(
                    evidence.RUNNER_PATH.read_bytes()
                ),
                "validatorSha256": evidence.sha256_bytes(
                    Path(evidence.__file__).read_bytes()
                ),
                "policySha256": evidence.sha256_bytes(
                    evidence.POLICY_PATH.read_bytes()
                ),
                "producerPolicySha256": evidence.sha256_bytes(
                    evidence.PRODUCER_POLICY_PATH.read_bytes()
                ),
                "scenarioPlanSha256": evidence.sha256_bytes(
                    evidence.canonical_json_bytes(self.plan())
                ),
                "workloadSha256": "9" * 64,
                "clientArtifactSha256": "8" * 64,
                "testArtifactSha256": "7" * 64,
            },
        )
        self.assertEqual(
            [item["role"] for item in manifest["artifacts"]],
            ["client-underlay", "external-observer"],
        )

    def test_manifest_serialization_is_deterministic(self) -> None:
        first = evidence.canonical_json_bytes(self.assemble())
        second = evidence.canonical_json_bytes(self.assemble())
        self.assertEqual(first, second)

    def test_validator_rejects_unapproved_manifest_producers(self) -> None:
        manifest = self.assemble()
        manifest["provenance"]["workloadSha256"] = "a" * 64
        with self.assertRaisesRegex(ValueError, "workload digest is not approved"):
            self.validate(manifest)

    def test_validator_rejects_unapproved_test_artifact(self) -> None:
        with self.assertRaisesRegex(ValueError, "test artifact digest is not approved"):
            evidence.enforce_producer_policy(
                client_collector_sha256="e" * 64,
                observer_collector_sha256="f" * 64,
                workload_sha256="9" * 64,
                client_artifact_sha256="8" * 64,
                test_artifact_sha256="a" * 64,
            )

    def test_assembler_rejects_observation_test_artifact_mismatch(self) -> None:
        observer = self.observation("external-observer")
        observer["testArtifactSha256"] = "a" * 64
        with self.assertRaisesRegex(ValueError, "test artifact digest"):
            self.assemble(observer=observer)

    def test_plan_test_artifact_must_match_runner(self) -> None:
        with self.assertRaisesRegex(ValueError, "test artifact digest"):
            evidence.validate_plan(
                self.plan(),
                expected_source_sha=self.source_sha,
                expected_correlation_id=self.correlation_id,
                expected_client_artifact_sha256="8" * 64,
                expected_test_artifact_sha256="a" * 64,
                applies_to="android-client-release",
            )

    def test_assembler_rejects_empty_producer_allowlist(self) -> None:
        evidence.write_canonical_json(
            evidence.PRODUCER_POLICY_PATH,
            {
                "version": "network_evidence_producers_v2",
                "clientCollectorSha256": [],
                "observerCollectorSha256": [],
                "workloadSha256": [],
                "clientArtifactSha256": [],
                "testArtifactSha256": [],
            },
        )
        with self.assertRaisesRegex(ValueError, "digest is not approved"):
            self.assemble()

    def test_repo_schema_pins_manifest_and_observation_versions(self) -> None:
        schema = json.loads(
            (
                ROOT / "quality/release-gates/network-evidence-manifest-v3.schema.json"
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
            set(schema["$defs"]["scenarioPlan"]["required"]),
            evidence.PLAN_FIELDS,
        )
        self.assertEqual(
            set(schema["$defs"]["planWindow"]["required"]),
            evidence.PLAN_WINDOW_FIELDS,
        )
        self.assertEqual(
            set(schema["properties"]["gateResults"]["additionalProperties"]["enum"]),
            {"PASS", "FAIL", "INCONCLUSIVE"},
        )
        v1_schema = json.loads(
            (
                ROOT / "quality/release-gates/network-evidence-manifest-v1.schema.json"
            ).read_text(encoding="utf-8")
        )
        self.assertEqual(
            v1_schema["properties"]["version"]["const"],
            "network_evidence_manifest_v1",
        )
        self.assertEqual(
            set(v1_schema["$defs"]["provenance"]["required"]),
            {
                "workflowPath",
                "workflowRunId",
                "workflowRunAttempt",
                "workloadSha256",
                "clientArtifactSha256",
            },
        )
        v2_schema = json.loads(
            (
                ROOT / "quality/release-gates/network-evidence-manifest-v2.schema.json"
            ).read_text(encoding="utf-8")
        )
        self.assertEqual(
            v2_schema["properties"]["version"]["const"],
            "network_evidence_manifest_v2",
        )
        for definition in ("provenance", "observation", "scenarioPlan"):
            self.assertNotIn(
                "testArtifactSha256", v2_schema["$defs"][definition]["required"]
            )

    def test_repo_schema_validates_emitted_manifest_and_observations(self) -> None:
        schema = json.loads(
            (
                ROOT / "quality/release-gates/network-evidence-manifest-v3.schema.json"
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
        plan_schema = {
            "$schema": "https://json-schema.org/draft/2020-12/schema",
            "$ref": "#/$defs/scenarioPlan",
            "$defs": schema["$defs"],
        }
        Draft202012Validator(plan_schema).validate(self.plan())

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
            plan=self.plan(),
            expected_role="client-underlay",
            expected_source_sha=self.source_sha,
            expected_correlation_id=self.correlation_id,
            vantage_id_sha256="5" * 64,
            network_id_sha256="7" * 64,
            collector_sha256="6" * 64,
            client_artifact_sha256="8" * 64,
            test_artifact_sha256="7" * 64,
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
                plan=self.plan(),
                expected_role="client-underlay",
                expected_source_sha=self.source_sha,
                expected_correlation_id=self.correlation_id,
                vantage_id_sha256="5" * 64,
                network_id_sha256="7" * 64,
                collector_sha256="6" * 64,
                client_artifact_sha256="8" * 64,
                test_artifact_sha256="7" * 64,
            )

    def test_missing_gate_window_is_rejected(self) -> None:
        observer = self.observation("external-observer")
        observer["windows"].pop()
        with self.assertRaisesRegex(ValueError, "window ids"):
            self.assemble(observer=observer)

    def test_startup_barrier_requires_complete_dual_vantage_packet_evidence(
        self,
    ) -> None:
        gate_id = "killswitch-tun-establish-native-ready"
        self.assertIn(gate_id, self.gate_ids)
        self.assertEqual(evidence.expected_kind(gate_id), "direct_window")

        for mutation, expected_error in (
            ("missing", "window ids"),
            ("missing-counter", "missing fields"),
            ("zero-control", "positive control"),
            ("mismatched", "overlap"),
        ):
            with self.subTest(mutation=mutation):
                observer = self.observation("external-observer")
                startup_window = next(
                    window for window in observer["windows"] if window["id"] == gate_id
                )
                if mutation == "missing":
                    observer["windows"].remove(startup_window)
                elif mutation == "missing-counter":
                    startup_window.pop("unexpectedPacketCount")
                elif mutation == "zero-control":
                    startup_window["expectedPacketCount"] = 0
                else:
                    startup_window["startedAtEpoch"] = self.finished_at + 1
                    startup_window["finishedAtEpoch"] = self.finished_at + 2

                with self.assertRaisesRegex(ValueError, expected_error):
                    self.assemble(observer=observer)

    def test_generic_reused_scenario_markers_are_rejected(self) -> None:
        plan = self.plan()
        plan["windows"][1]["actionMarkerSha256"] = plan["windows"][0][
            "actionMarkerSha256"
        ]
        with self.assertRaisesRegex(ValueError, "correlation and gate"):
            evidence.validate_plan(
                plan,
                expected_source_sha=self.source_sha,
                expected_correlation_id=self.correlation_id,
                expected_client_artifact_sha256="8" * 64,
                expected_test_artifact_sha256="7" * 64,
                applies_to="android-client-release",
            )

    def test_unique_but_unbound_scenario_marker_is_rejected(self) -> None:
        plan = self.plan()
        plan["windows"][0]["actionMarkerSha256"] = "f" * 64
        with self.assertRaisesRegex(ValueError, "correlation and gate"):
            evidence.validate_plan(
                plan,
                expected_source_sha=self.source_sha,
                expected_correlation_id=self.correlation_id,
                expected_client_artifact_sha256="8" * 64,
                expected_test_artifact_sha256="7" * 64,
                applies_to="android-client-release",
            )

    def test_marker_from_another_correlation_is_rejected(self) -> None:
        plan = self.plan()
        window = plan["windows"][0]
        window["actionMarkerSha256"] = evidence.derive_marker(
            "c" * 64, window["id"], window["kind"], "action"
        )
        with self.assertRaisesRegex(ValueError, "correlation and gate"):
            evidence.validate_plan(
                plan,
                expected_source_sha=self.source_sha,
                expected_correlation_id=self.correlation_id,
                expected_client_artifact_sha256="8" * 64,
                expected_test_artifact_sha256="7" * 64,
                applies_to="android-client-release",
            )

    def test_cross_purpose_scenario_marker_reuse_is_rejected(self) -> None:
        plan = self.plan()
        plan["windows"][1]["outcomeMarkerSha256"] = plan["windows"][0][
            "actionMarkerSha256"
        ]
        with self.assertRaisesRegex(ValueError, "correlation and gate"):
            evidence.validate_plan(
                plan,
                expected_source_sha=self.source_sha,
                expected_correlation_id=self.correlation_id,
                expected_client_artifact_sha256="8" * 64,
                expected_test_artifact_sha256="7" * 64,
                applies_to="android-client-release",
            )

    def test_observation_without_gate_action_outcome_is_rejected(self) -> None:
        observer = self.observation("external-observer")
        observer["windows"][0]["actionObservedCount"] = 0
        with self.assertRaisesRegex(ValueError, "actionObservedCount"):
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
        with self.assertRaisesRegex(ValueError, "not all PASS"):
            evidence.validate_manifest(
                manifest,
                artifact_root=self.root,
                expected_source_sha=self.source_sha,
                applies_to="android-client-release",
                current_epoch=self.finished_at + 4,
                max_age_seconds=300,
                require_pass=True,
            )

    def test_all_pass_manifest_is_not_releasable_with_unready_actions(self) -> None:
        manifest = self.assemble()

        with self.assertRaisesRegex(ValueError, "not production ready"):
            evidence.validate_manifest(
                manifest,
                artifact_root=self.root,
                expected_source_sha=self.source_sha,
                applies_to="android-client-release",
                current_epoch=self.finished_at + 4,
                max_age_seconds=300,
                require_pass=True,
            )

    def test_unready_action_override_allows_synthetic_test_bundle(self) -> None:
        manifest = self.assemble()

        self.assertEqual(
            evidence.validate_manifest(
                manifest,
                artifact_root=self.root,
                expected_source_sha=self.source_sha,
                applies_to="android-client-release",
                current_epoch=self.finished_at + 4,
                max_age_seconds=300,
                require_pass=True,
                allow_unready_actions_for_synthetic_test=True,
            )["scenarioCount"],
            10,
        )

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

    def test_selected_execution_must_match_provenance(self) -> None:
        manifest = self.assemble()
        with self.assertRaisesRegex(ValueError, "executionId"):
            evidence.validate_manifest(
                manifest,
                artifact_root=self.root,
                expected_source_sha=self.source_sha,
                applies_to="android-client-release",
                current_epoch=self.finished_at + 4,
                max_age_seconds=300,
                expected_execution_kind="local",
                expected_execution_id="other-run",
                expected_execution_attempt=1,
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
        self.prepare_runner_source()
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
