#!/usr/bin/env python3
"""Fail-closed contract tests for Android ordinary release results."""

from __future__ import annotations

import importlib.util
import hashlib
import json
import os
import subprocess
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


producer = load_module(
    "android_ordinary_results",
    ROOT / "scripts/ci/produce_android_ordinary_gate_results.py",
)
gates = load_module(
    "ordinary_results_gate_checker",
    ROOT / "scripts/ci/check_dns_ipv6_killswitch_gates.py",
)
raw_evidence = producer.android_ordinary_raw_evidence


class AndroidOrdinaryGateResultsTest(unittest.TestCase):
    source_sha = "a" * 40

    def results(self) -> dict:
        return producer.all_failure_results(self.source_sha)

    def validate(self, results: dict) -> dict:
        return gates.validate_results_document(
            gates.load_json(gates.POLICY_PATH),
            results,
            expected_source_sha=self.source_sha,
            applies_to="android-client-release",
        )

    def create_raw_bundle(self, directory: Path) -> tuple[Path, Path, Path, dict]:
        artifact_root = directory / "artifacts"
        artifact_root.mkdir(mode=0o700)
        artifact_root.chmod(0o700)
        app_apk = directory / "app.apk"
        test_apk = directory / "test.apk"
        app_apk.write_bytes(b"app-apk")
        test_apk.write_bytes(b"test-apk")
        actions = []
        now_epoch_ms = int(time.time() * 1000)
        for action_index, spec in enumerate(raw_evidence.ACTION_SPECS):
            window_started = now_epoch_ms - 20_000 + action_index * 2_000
            window_finished = window_started + 1_000
            artifacts = []
            for kind in raw_evidence.ARTIFACT_KINDS:
                name = f"{spec.action_id}.{kind}.raw"
                payload = f"{spec.action_id}:{kind}\n".encode()
                path = artifact_root / name
                path.write_bytes(payload)
                path.chmod(0o600)
                artifacts.append(
                    {
                        "kind": kind,
                        "path": name,
                        "sha256": hashlib.sha256(payload).hexdigest(),
                        "sizeBytes": len(payload),
                        "vantage": raw_evidence.ARTIFACT_VANTAGES[kind],
                        "windowFinishedAtEpochMs": window_finished,
                        "windowStartedAtEpochMs": window_started,
                    }
                )
            actions.append(
                {
                    "actionId": spec.action_id,
                    "artifacts": artifacts,
                    "correlationId": hashlib.sha256(
                        f"correlation:{spec.action_id}".encode()
                    ).hexdigest(),
                    "gateIds": list(spec.gate_ids),
                    "windowFinishedAtEpochMs": window_finished,
                    "windowStartedAtEpochMs": window_started,
                }
            )
        manifest = {
            "actions": actions,
            "appApkSha256": hashlib.sha256(app_apk.read_bytes()).hexdigest(),
            "artifactRoot": str(artifact_root),
            "createdAtEpochMs": now_epoch_ms,
            "runId": hashlib.sha256(b"ordinary-raw-run").hexdigest(),
            "sourceSha": self.source_sha,
            "testApkSha256": hashlib.sha256(test_apk.read_bytes()).hexdigest(),
            "version": raw_evidence.BUNDLE_VERSION,
        }
        manifest_path = directory / "manifest.json"
        manifest_path.write_bytes(raw_evidence.canonical_json_bytes(manifest))
        manifest_path.chmod(0o600)
        return manifest_path, app_apk, test_apk, manifest

    def rewrite_manifest(self, path: Path, manifest: dict) -> None:
        path.write_bytes(raw_evidence.canonical_json_bytes(manifest))
        path.chmod(0o600)

    def assert_invalid_evidence_preserves_unproven_output(self, mutation: str) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                directory
            )
            if mutation == "malformed":
                manifest_path.write_bytes(b"{malformed\n")
            elif mutation == "noncanonical":
                manifest_path.write_text(json.dumps(manifest, indent=2))
            elif mutation == "digest":
                manifest["actions"][0]["artifacts"][0]["sha256"] = "0" * 64
                self.rewrite_manifest(manifest_path, manifest)
            elif mutation == "inventory":
                manifest["actions"][0]["artifacts"].pop()
                self.rewrite_manifest(manifest_path, manifest)
            else:
                self.fail(f"unknown mutation {mutation}")
            manifest_path.chmod(0o600)
            output_parent = directory / "output"
            output_parent.mkdir(mode=0o700)
            output = output_parent / "results.json"
            output.write_text('{"gateResults":{"forged":"PASS"}}')
            output.chmod(0o600)
            before = output.read_bytes()
            status = producer.main(
                [
                    "--output",
                    str(output),
                    "--raw-manifest",
                    str(manifest_path),
                    "--app-apk",
                    str(app_apk),
                    "--test-apk",
                    str(test_apk),
                ]
            )
            self.assertEqual(status, 2)
            self.assertEqual(output.read_bytes(), before)

    def test_inventory_matches_exact_android_ordinary_policy_set(self) -> None:
        policy = gates.load_json(gates.POLICY_PATH)
        ordinary = gates.applicable_gate_ids(
            policy, applies_to="android-client-release"
        ) - gates.dual_vantage_gate_ids(policy, applies_to="android-client-release")
        self.assertEqual(set(producer.ORDINARY_GATE_IDS), ordinary)
        self.assertEqual(len(ordinary), 11)
        action_gates = {
            gate_id for spec in raw_evidence.ACTION_SPECS for gate_id in spec.gate_ids
        }
        self.assertEqual(action_gates, ordinary)
        self.assertEqual(len(raw_evidence.ACTION_SPECS), 7)

    def test_relative_evidence_inputs_are_rejected_before_open(self) -> None:
        for relative in (Path("manifest.json"), Path("app.apk"), Path("test.apk")):
            with (
                self.subTest(path=relative),
                self.assertRaisesRegex(ValueError, "INPUT_PATH_INVALID"),
            ):
                producer.pin_input(relative)

    def test_malformed_evidence_preserves_unproven_stale_output(self) -> None:
        self.assert_invalid_evidence_preserves_unproven_output("malformed")

    def test_noncanonical_evidence_preserves_unproven_stale_output(self) -> None:
        self.assert_invalid_evidence_preserves_unproven_output("noncanonical")

    def test_digest_failure_preserves_unproven_stale_output(self) -> None:
        self.assert_invalid_evidence_preserves_unproven_output("digest")

    def test_inventory_failure_preserves_unproven_stale_output(self) -> None:
        self.assert_invalid_evidence_preserves_unproven_output("inventory")

    def test_results_shaped_raw_artifact_is_never_recovered_as_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                directory
            )
            artifact_entry = manifest["actions"][0]["artifacts"][0]
            artifact = Path(manifest["artifactRoot"]) / artifact_entry["path"]
            mimicking_payload = raw_evidence.canonical_json_bytes(
                {
                    "appliesTo": producer.APPLIES_TO,
                    "gateResults": {
                        gate_id: {"state": "PASS"}
                        for gate_id in producer.ORDINARY_GATE_IDS
                    },
                    "sourceSha": self.source_sha,
                    "version": producer.RESULTS_VERSION,
                }
            )
            artifact.write_bytes(mimicking_payload)
            artifact.chmod(0o600)
            artifact_entry["sizeBytes"] = len(mimicking_payload)
            artifact_entry["sha256"] = hashlib.sha256(mimicking_payload).hexdigest()
            self.rewrite_manifest(manifest_path, manifest)
            manifest_path.write_bytes(b"{malformed\n")
            manifest_path.chmod(0o600)
            status = producer.main(
                [
                    "--output",
                    str(artifact),
                    "--raw-manifest",
                    str(manifest_path),
                    "--app-apk",
                    str(app_apk),
                    "--test-apk",
                    str(test_apk),
                ]
            )
            self.assertEqual(status, 2)
            self.assertEqual(artifact.read_bytes(), mimicking_payload)

    def test_producer_has_no_pluggable_or_false_green_path(self) -> None:
        self.assertFalse(producer.SOURCE_OWNED_VERIFIER_AVAILABLE)
        self.assertFalse(hasattr(producer, "APPROVED_COLLECTORS"))
        self.assertFalse(hasattr(producer, "run_approved_collector"))
        with self.assertRaisesRegex(ValueError, producer.UNAVAILABLE_CODE):
            producer.validate_pass_results({})

    def test_complete_structured_failure_is_checker_compatible(self) -> None:
        results = self.results()
        self.assertEqual(self.validate(results), results)
        self.assertEqual(set(results["gateResults"]), set(producer.ORDINARY_GATE_IDS))
        self.assertTrue(
            all(value["state"] == "FAIL" for value in results["gateResults"].values())
        )
        evaluation = gates.evaluate_results(
            gates.load_json(gates.POLICY_PATH),
            results,
            applies_to="android-client-release",
        )
        self.assertTrue(
            any(
                producer.UNAVAILABLE_CODE in violation
                for violation in evaluation["violations"]
            )
        )

    def test_output_is_deterministic_and_canonical(self) -> None:
        first = self.results()
        second = self.results()
        self.assertEqual(
            producer.canonical_json_bytes(first), producer.canonical_json_bytes(second)
        )
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "results.json"
            producer.write_canonical_json(output, first)
            self.assertEqual(output.read_bytes(), producer.canonical_json_bytes(first))
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)

    def test_private_raw_bundle_preflight_stops_at_semantic_blockers(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, _ = self.create_raw_bundle(directory)
            provenance = raw_evidence.validate_raw_bundle(
                manifest_path,
                expected_source_sha=self.source_sha,
                app_apk=app_apk,
                test_apk=test_apk,
            )
            self.assertEqual(provenance["actionCount"], 7)
            self.assertEqual(provenance["artifactCount"], 21)
            self.assertEqual(
                set(provenance["semanticBlockers"]), set(producer.ORDINARY_GATE_IDS)
            )
            results = producer.semantic_failure_results(self.source_sha, provenance)
            self.assertEqual(self.validate(results), results)
            self.assertFalse(results["rawBundleProvenance"]["productionReady"])
            self.assertTrue(
                all(
                    value["state"] == "FAIL" and value["reason"].startswith("SEMANTIC_")
                    for value in results["gateResults"].values()
                )
            )

    def test_cli_emits_verifier_owned_semantic_failures_for_valid_raw_bundle(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, _ = self.create_raw_bundle(directory)
            output = directory / "results.json"
            with (
                mock.patch.object(
                    producer, "current_head_sha", return_value=self.source_sha
                ),
                mock.patch.object(
                    producer, "current_source_sha", return_value=self.source_sha
                ),
            ):
                status = producer.main(
                    [
                        "--output",
                        str(output),
                        "--raw-manifest",
                        str(manifest_path),
                        "--app-apk",
                        str(app_apk),
                        "--test-apk",
                        str(test_apk),
                    ]
                )
            self.assertEqual(status, 1)
            results = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(self.validate(results), results)
            self.assertEqual(
                output.read_bytes(), producer.canonical_json_bytes(results)
            )
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)

    def test_cli_refuses_output_aliasing_direct_inputs_without_overwrite(self) -> None:
        aliases = ("manifest", "app", "test")
        for alias in aliases:
            with self.subTest(alias=alias):
                with tempfile.TemporaryDirectory() as directory_name:
                    directory = Path(directory_name)
                    manifest_path, app_apk, test_apk, _ = self.create_raw_bundle(
                        directory
                    )
                    output = {
                        "manifest": manifest_path,
                        "app": app_apk,
                        "test": test_apk,
                    }[alias]
                    before = output.read_bytes()
                    status = producer.main(
                        [
                            "--output",
                            str(output),
                            "--raw-manifest",
                            str(manifest_path),
                            "--app-apk",
                            str(app_apk),
                            "--test-apk",
                            str(test_apk),
                        ]
                    )
                    self.assertEqual(status, 2)
                    self.assertEqual(output.read_bytes(), before)

    def test_cli_refuses_output_inside_artifact_root_without_overwrite(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                directory
            )
            output = Path(manifest["artifactRoot"]) / "caller-summary.json"
            output.write_bytes(b"raw-evidence")
            output.chmod(0o600)
            status = producer.main(
                [
                    "--output",
                    str(output),
                    "--raw-manifest",
                    str(manifest_path),
                    "--app-apk",
                    str(app_apk),
                    "--test-apk",
                    str(test_apk),
                ]
            )
            self.assertEqual(status, 2)
            self.assertEqual(output.read_bytes(), b"raw-evidence")

            new_output = Path(manifest["artifactRoot"]) / "new-results.json"
            status = producer.main(
                [
                    "--output",
                    str(new_output),
                    "--raw-manifest",
                    str(manifest_path),
                    "--app-apk",
                    str(app_apk),
                    "--test-apk",
                    str(test_apk),
                ]
            )
            self.assertEqual(status, 2)
            self.assertFalse(new_output.exists())

            root_alias = directory / "artifact-root-alias"
            root_alias.symlink_to(
                Path(manifest["artifactRoot"]), target_is_directory=True
            )
            aliased_output = root_alias / "new-results.json"
            status = producer.main(
                [
                    "--output",
                    str(aliased_output),
                    "--raw-manifest",
                    str(manifest_path),
                    "--app-apk",
                    str(app_apk),
                    "--test-apk",
                    str(test_apk),
                ]
            )
            self.assertEqual(status, 2)
            self.assertFalse(aliased_output.exists())

    def test_non_normalized_root_cannot_create_output_parent_in_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                directory
            )
            artifact_root = Path(manifest["artifactRoot"])
            manifest["artifactRoot"] = str(
                artifact_root.parent / artifact_root.name / ".." / artifact_root.name
            )
            self.rewrite_manifest(manifest_path, manifest)
            entries_before = set(artifact_root.iterdir())
            output = artifact_root / "nested-output" / "results.json"
            status = producer.main(
                [
                    "--output",
                    str(output),
                    "--raw-manifest",
                    str(manifest_path),
                    "--app-apk",
                    str(app_apk),
                    "--test-apk",
                    str(test_apk),
                ]
            )
            self.assertEqual(status, 2)
            self.assertFalse(output.parent.exists())
            self.assertEqual(set(artifact_root.iterdir()), entries_before)

    def test_cli_refuses_hardlinked_output_without_overwrite(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, _ = self.create_raw_bundle(directory)
            output = directory / "results.json"
            os.link(app_apk, output)
            before = app_apk.read_bytes()
            status = producer.main(
                [
                    "--output",
                    str(output),
                    "--raw-manifest",
                    str(manifest_path),
                    "--app-apk",
                    str(app_apk),
                    "--test-apk",
                    str(test_apk),
                ]
            )
            self.assertEqual(status, 2)
            self.assertEqual(app_apk.read_bytes(), before)

    def test_cli_refuses_untrusted_manifest_before_overwriting_artifact(self) -> None:
        mutations = ("noncanonical", "privacy", "malformed")
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with tempfile.TemporaryDirectory() as directory_name:
                    directory = Path(directory_name)
                    manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                        directory
                    )
                    artifact = manifest["actions"][0]["artifacts"][0]
                    output = Path(manifest["artifactRoot"]) / artifact["path"]
                    before = output.read_bytes()
                    if mutation == "noncanonical":
                        manifest_path.write_text(
                            json.dumps(manifest, indent=2), encoding="utf-8"
                        )
                        manifest_path.chmod(0o600)
                    elif mutation == "privacy":
                        manifest_path.chmod(0o644)
                    else:
                        manifest_path.write_bytes(b"{malformed\n")
                        manifest_path.chmod(0o600)
                    status = producer.main(
                        [
                            "--output",
                            str(output),
                            "--raw-manifest",
                            str(manifest_path),
                            "--app-apk",
                            str(app_apk),
                            "--test-apk",
                            str(test_apk),
                        ]
                    )
                    self.assertEqual(status, 2)
                    self.assertEqual(output.read_bytes(), before)

    def test_fd_relative_output_survives_parent_symlink_swap(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                directory
            )
            artifact = manifest["actions"][0]["artifacts"][0]
            raw_artifact = Path(manifest["artifactRoot"]) / artifact["path"]
            raw_before = raw_artifact.read_bytes()
            output_parent = directory / "output"
            output_parent.mkdir(mode=0o700)
            moved_parent = directory / "opened-output"
            output = output_parent / artifact["path"]

            def swap_parent() -> str:
                output_parent.rename(moved_parent)
                output_parent.symlink_to(
                    Path(manifest["artifactRoot"]), target_is_directory=True
                )
                return self.source_sha

            with (
                mock.patch.object(
                    producer, "current_head_sha", side_effect=swap_parent
                ),
                mock.patch.object(
                    producer, "current_source_sha", return_value=self.source_sha
                ),
            ):
                status = producer.main(
                    [
                        "--output",
                        str(output),
                        "--raw-manifest",
                        str(manifest_path),
                        "--app-apk",
                        str(app_apk),
                        "--test-apk",
                        str(test_apk),
                    ]
                )
            self.assertEqual(status, 1)
            self.assertEqual(raw_artifact.read_bytes(), raw_before)
            results = json.loads((moved_parent / artifact["path"]).read_text())
            self.assertTrue(
                all(
                    value["state"] == "FAIL"
                    for value in results["gateResults"].values()
                )
            )

    def test_reserved_output_does_not_delete_input_renamed_onto_leaf(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, _ = self.create_raw_bundle(directory)
            output_parent = directory / "output"
            output_parent.mkdir(mode=0o700)
            output = output_parent / "results.json"
            app_before = app_apk.read_bytes()

            def move_app_onto_output() -> str:
                app_apk.rename(output)
                return self.source_sha

            with mock.patch.object(
                producer, "current_head_sha", side_effect=move_app_onto_output
            ):
                status = producer.main(
                    [
                        "--output",
                        str(output),
                        "--raw-manifest",
                        str(manifest_path),
                        "--app-apk",
                        str(app_apk),
                        "--test-apk",
                        str(test_apk),
                    ]
                )
            self.assertEqual(status, 2)
            self.assertFalse(app_apk.exists())
            self.assertEqual(output.read_bytes(), app_before)

    def test_preflight_pins_input_renamed_onto_absent_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, _ = self.create_raw_bundle(directory)
            output_parent = directory / "output"
            output_parent.mkdir(mode=0o700)
            output = output_parent / "results.json"
            app_before = app_apk.read_bytes()
            load_manifest = raw_evidence.load_private_manifest_descriptor

            def move_app_during_manifest_load(
                descriptor: int, metadata: os.stat_result
            ) -> tuple[dict, bytes]:
                loaded = load_manifest(descriptor, metadata)
                app_apk.rename(output)
                return loaded

            with mock.patch.object(
                raw_evidence,
                "load_private_manifest_descriptor",
                side_effect=move_app_during_manifest_load,
            ):
                status = producer.main(
                    [
                        "--output",
                        str(output),
                        "--raw-manifest",
                        str(manifest_path),
                        "--app-apk",
                        str(app_apk),
                        "--test-apk",
                        str(test_apk),
                    ]
                )
            self.assertEqual(status, 2)
            self.assertFalse(app_apk.exists())
            self.assertEqual(output.read_bytes(), app_before)

    def test_preflight_pins_artifact_root_renamed_onto_output_parent(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                directory
            )
            artifact_root = Path(manifest["artifactRoot"])
            artifact = manifest["actions"][0]["artifacts"][0]
            raw_artifact = artifact_root / artifact["path"]
            artifact_before = raw_artifact.read_bytes()
            output_parent = directory / "output"
            output = output_parent / artifact["path"]
            pin_artifact_root = producer.pin_artifact_root

            def move_root_after_pin(path: Path) -> producer.PinnedDirectory:
                guard = pin_artifact_root(path)
                artifact_root.rename(output_parent)
                return guard

            with mock.patch.object(
                producer, "pin_artifact_root", side_effect=move_root_after_pin
            ):
                status = producer.main(
                    [
                        "--output",
                        str(output),
                        "--raw-manifest",
                        str(manifest_path),
                        "--app-apk",
                        str(app_apk),
                        "--test-apk",
                        str(test_apk),
                    ]
                )
            self.assertEqual(status, 2)
            self.assertFalse(artifact_root.exists())
            self.assertEqual(output.read_bytes(), artifact_before)

    def test_transient_decoy_manifest_cannot_redirect_output_exclusion(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                directory
            )
            artifact_root = Path(manifest["artifactRoot"])
            artifact = manifest["actions"][0]["artifacts"][0]
            output = artifact_root / artifact["path"]
            artifact_before = output.read_bytes()
            decoy_root = directory / "decoy-root"
            decoy_root.mkdir(mode=0o700)
            decoy_manifest = directory / "decoy-manifest.json"
            decoy = dict(manifest)
            decoy["artifactRoot"] = str(decoy_root)
            self.rewrite_manifest(decoy_manifest, decoy)
            saved_manifest = directory / "saved-manifest.json"
            load_manifest = raw_evidence.load_private_manifest_descriptor

            def transient_decoy(
                descriptor: int, metadata: os.stat_result
            ) -> tuple[dict, bytes]:
                manifest_path.rename(saved_manifest)
                decoy_manifest.rename(manifest_path)
                try:
                    return load_manifest(descriptor, metadata)
                finally:
                    manifest_path.rename(decoy_manifest)
                    saved_manifest.rename(manifest_path)

            with mock.patch.object(
                raw_evidence,
                "load_private_manifest_descriptor",
                side_effect=transient_decoy,
            ):
                status = producer.main(
                    [
                        "--output",
                        str(output),
                        "--raw-manifest",
                        str(manifest_path),
                        "--app-apk",
                        str(app_apk),
                        "--test-apk",
                        str(test_apk),
                    ]
                )
            self.assertEqual(status, 2)
            self.assertEqual(output.read_bytes(), artifact_before)

    def test_transient_declared_root_swap_cannot_redirect_output_exclusion(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                directory
            )
            artifact_root = Path(manifest["artifactRoot"])
            artifact = manifest["actions"][0]["artifacts"][0]
            output = artifact_root / artifact["path"]
            artifact_before = output.read_bytes()
            decoy_root = directory / "decoy-root"
            decoy_root.mkdir(mode=0o700)
            saved_root = directory / "saved-root"
            open_file = os.open
            swapped = False

            def transient_root_swap(
                path: str | Path, flags: int, *args, **kwargs
            ) -> int:
                nonlocal swapped
                if Path(path) == artifact_root and not swapped:
                    swapped = True
                    artifact_root.rename(saved_root)
                    decoy_root.rename(artifact_root)
                    try:
                        return open_file(path, flags, *args, **kwargs)
                    finally:
                        artifact_root.rename(decoy_root)
                        saved_root.rename(artifact_root)
                return open_file(path, flags, *args, **kwargs)

            with mock.patch.object(
                producer.os, "open", side_effect=transient_root_swap
            ):
                status = producer.main(
                    [
                        "--output",
                        str(output),
                        "--raw-manifest",
                        str(manifest_path),
                        "--app-apk",
                        str(app_apk),
                        "--test-apk",
                        str(test_apk),
                    ]
                )
            self.assertEqual(status, 2)
            self.assertEqual(output.read_bytes(), artifact_before)

    def test_artifact_swap_after_read_is_rejected_before_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                directory
            )
            artifact_root = Path(manifest["artifactRoot"])
            target = artifact_root / manifest["actions"][0]["artifacts"][0]["path"]
            replacement_bytes = target.read_bytes()
            moved = artifact_root / "moved-original"
            output_parent = directory / "output"
            output_parent.mkdir(mode=0o700)
            output = output_parent / "results.json"
            listdir = os.listdir
            swapped = False

            def swap_after_inventory(path: int) -> list[str]:
                nonlocal swapped
                entries = listdir(path)
                if not swapped:
                    swapped = True
                    target.rename(moved)
                    target.write_bytes(replacement_bytes)
                    target.chmod(0o600)
                return entries

            with (
                mock.patch.object(
                    raw_evidence.os, "listdir", side_effect=swap_after_inventory
                ),
                mock.patch.object(
                    producer, "current_head_sha", return_value=self.source_sha
                ),
                mock.patch.object(
                    producer, "current_source_sha", return_value=self.source_sha
                ),
            ):
                status = producer.main(
                    [
                        "--output",
                        str(output),
                        "--raw-manifest",
                        str(manifest_path),
                        "--app-apk",
                        str(app_apk),
                        "--test-apk",
                        str(test_apk),
                    ]
                )
            self.assertEqual(status, 2)
            self.assertFalse(output.exists())

    def test_artifact_moved_to_output_after_preflight_is_never_overwritten(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                directory
            )
            artifact_root = Path(manifest["artifactRoot"])
            artifact = artifact_root / manifest["actions"][0]["artifacts"][0]["path"]
            artifact_before = artifact.read_bytes()
            output_parent = directory / "output"
            output_parent.mkdir(mode=0o700)
            output = output_parent / "results.json"
            open_output = producer.open_output_destination
            moved = False

            def move_artifact_before_output(*args, **kwargs):
                nonlocal moved
                if not moved:
                    moved = True
                    artifact.rename(output)
                return open_output(*args, **kwargs)

            with mock.patch.object(
                producer,
                "open_output_destination",
                side_effect=move_artifact_before_output,
            ):
                status = producer.main(
                    [
                        "--output",
                        str(output),
                        "--raw-manifest",
                        str(manifest_path),
                        "--app-apk",
                        str(app_apk),
                        "--test-apk",
                        str(test_apk),
                    ]
                )
            self.assertEqual(status, 2)
            self.assertFalse(artifact.exists())
            self.assertEqual(output.read_bytes(), artifact_before)

    def test_artifact_revalidation_hashes_the_current_openat_descriptor(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            root = Path(directory_name)
            artifact_path = root / "artifact.raw"
            payload = b"retained artifact"
            artifact_path.write_bytes(payload)
            artifact_path.chmod(0o600)
            retained_fd = os.open(
                artifact_path, os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW
            )
            root_fd = os.open(root, os.O_RDONLY | os.O_CLOEXEC | os.O_DIRECTORY)
            pinned = raw_evidence.PinnedArtifact(
                relative=artifact_path.name,
                descriptor=retained_fd,
                metadata=os.fstat(retained_fd),
                digest=hashlib.sha256(payload).hexdigest(),
                label="artifact",
            )
            read_descriptor = raw_evidence._read_descriptor
            observed_descriptors: list[int] = []

            def observe_current_descriptor(
                descriptor: int, metadata: os.stat_result, *, label: str
            ) -> bytes:
                observed_descriptors.append(descriptor)
                return read_descriptor(descriptor, metadata, label=label)

            try:
                with mock.patch.object(
                    raw_evidence,
                    "_read_descriptor",
                    side_effect=observe_current_descriptor,
                ):
                    pinned.revalidate(root_fd)
                self.assertEqual(len(observed_descriptors), 1)
                self.assertNotEqual(observed_descriptors[0], retained_fd)
            finally:
                pinned.close()
                os.close(root_fd)

    def test_artifact_swap_during_publication_replaces_provenance_with_failure(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                directory
            )
            artifact_root = Path(manifest["artifactRoot"])
            target = artifact_root / manifest["actions"][0]["artifacts"][0]["path"]
            replacement_bytes = target.read_bytes()
            moved = artifact_root / "moved-during-publication"
            output_parent = directory / "output"
            output_parent.mkdir(mode=0o700)
            output = output_parent / "results.json"
            publish_results = producer.publish_results
            swapped = False

            def swap_before_provenance_publish(
                destination: producer.OutputDestination,
                path: Path,
                value: dict,
            ) -> bool:
                nonlocal swapped
                if "rawBundleProvenance" in value and not swapped:
                    swapped = True
                    target.rename(moved)
                    target.write_bytes(replacement_bytes)
                    target.chmod(0o600)
                return publish_results(destination, path, value)

            with (
                mock.patch.object(
                    producer,
                    "publish_results",
                    side_effect=swap_before_provenance_publish,
                ),
                mock.patch.object(
                    producer, "current_head_sha", return_value=self.source_sha
                ),
                mock.patch.object(
                    producer, "current_source_sha", return_value=self.source_sha
                ),
            ):
                status = producer.main(
                    [
                        "--output",
                        str(output),
                        "--raw-manifest",
                        str(manifest_path),
                        "--app-apk",
                        str(app_apk),
                        "--test-apk",
                        str(test_apk),
                    ]
                )
            self.assertEqual(status, 1)
            results = json.loads(output.read_text())
            self.assertNotIn("rawBundleProvenance", results)
            self.assertTrue(
                all(
                    value["state"] == "FAIL"
                    and (
                        "ARTIFACT_CHANGED" in value["reason"]
                        or "INVENTORY_MISMATCH" in value["reason"]
                    )
                    for value in results["gateResults"].values()
                )
            )

    def test_root_inventory_and_mode_changes_during_publication_drop_provenance(
        self,
    ) -> None:
        for mutation, expected_code in (
            ("extra", "INVENTORY_MISMATCH"),
            ("mode", "ARTIFACT_ROOT_CHANGED"),
        ):
            with (
                self.subTest(mutation=mutation),
                tempfile.TemporaryDirectory() as directory_name,
            ):
                directory = Path(directory_name)
                manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                    directory
                )
                artifact_root = Path(manifest["artifactRoot"])
                output_parent = directory / "output"
                output_parent.mkdir(mode=0o700)
                output = output_parent / "results.json"
                publish_results = producer.publish_results
                mutated = False

                def mutate_before_provenance_publish(
                    destination: producer.OutputDestination,
                    path: Path,
                    value: dict,
                ) -> bool:
                    nonlocal mutated
                    if "rawBundleProvenance" in value and not mutated:
                        mutated = True
                        if mutation == "extra":
                            extra = artifact_root / "caller-summary.json"
                            extra.write_bytes(b"caller summary")
                            extra.chmod(0o600)
                        else:
                            artifact_root.chmod(0o755)
                    return publish_results(destination, path, value)

                with (
                    mock.patch.object(
                        producer,
                        "publish_results",
                        side_effect=mutate_before_provenance_publish,
                    ),
                    mock.patch.object(
                        producer, "current_head_sha", return_value=self.source_sha
                    ),
                    mock.patch.object(
                        producer, "current_source_sha", return_value=self.source_sha
                    ),
                ):
                    status = producer.main(
                        [
                            "--output",
                            str(output),
                            "--raw-manifest",
                            str(manifest_path),
                            "--app-apk",
                            str(app_apk),
                            "--test-apk",
                            str(test_apk),
                        ]
                    )
                self.assertEqual(status, 1)
                results = json.loads(output.read_text())
                self.assertNotIn("rawBundleProvenance", results)
                self.assertTrue(
                    all(
                        value["state"] == "FAIL" and expected_code in value["reason"]
                        for value in results["gateResults"].values()
                    )
                )

    def test_evidence_expiry_crossing_during_publication_drops_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                directory
            )
            clock = [manifest["createdAtEpochMs"]]
            output_parent = directory / "output"
            output_parent.mkdir(mode=0o700)
            output = output_parent / "results.json"
            publish_results = producer.publish_results

            def cross_expiry_before_provenance_publish(
                destination: producer.OutputDestination,
                path: Path,
                value: dict,
            ) -> bool:
                if "rawBundleProvenance" in value:
                    clock[0] = (
                        manifest["createdAtEpochMs"]
                        + raw_evidence.MAX_EVIDENCE_AGE_MS
                        + 1
                    )
                return publish_results(destination, path, value)

            with (
                mock.patch.object(
                    raw_evidence, "current_epoch_ms", side_effect=lambda: clock[0]
                ),
                mock.patch.object(
                    producer,
                    "publish_results",
                    side_effect=cross_expiry_before_provenance_publish,
                ),
                mock.patch.object(
                    producer, "current_head_sha", return_value=self.source_sha
                ),
                mock.patch.object(
                    producer, "current_source_sha", return_value=self.source_sha
                ),
            ):
                status = producer.main(
                    [
                        "--output",
                        str(output),
                        "--raw-manifest",
                        str(manifest_path),
                        "--app-apk",
                        str(app_apk),
                        "--test-apk",
                        str(test_apk),
                    ]
                )
            self.assertEqual(status, 1)
            results = json.loads(output.read_text())
            self.assertNotIn("rawBundleProvenance", results)
            self.assertTrue(
                all(
                    value["state"] == "FAIL" and "EVIDENCE_STALE" in value["reason"]
                    for value in results["gateResults"].values()
                )
            )

    def test_mid_revalidation_root_changes_drop_provenance(self) -> None:
        for mutation, expected_code in (
            ("extra", "INVENTORY_MISMATCH"),
            ("mode", "ARTIFACT_ROOT_CHANGED"),
        ):
            with (
                self.subTest(mutation=mutation),
                tempfile.TemporaryDirectory() as directory_name,
            ):
                directory = Path(directory_name)
                manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                    directory
                )
                artifact_root = Path(manifest["artifactRoot"])
                output_parent = directory / "output"
                output_parent.mkdir(mode=0o700)
                output = output_parent / "results.json"
                publish_results = producer.publish_results
                revalidate_artifact = raw_evidence.PinnedArtifact.revalidate
                publication_started = False
                artifact_checks = 0

                def start_publication(
                    destination: producer.OutputDestination,
                    path: Path,
                    value: dict,
                ) -> bool:
                    nonlocal publication_started
                    if "rawBundleProvenance" in value:
                        publication_started = True
                    return publish_results(destination, path, value)

                def mutate_mid_revalidation(
                    artifact: raw_evidence.PinnedArtifact, root_descriptor: int
                ) -> None:
                    nonlocal artifact_checks
                    revalidate_artifact(artifact, root_descriptor)
                    if publication_started:
                        artifact_checks += 1
                        if artifact_checks == 10:
                            if mutation == "extra":
                                extra = artifact_root / "mid-revalidation-extra"
                                extra.write_bytes(b"extra")
                                extra.chmod(0o600)
                            else:
                                artifact_root.chmod(0o755)

                with (
                    mock.patch.object(
                        producer, "publish_results", side_effect=start_publication
                    ),
                    mock.patch.object(
                        raw_evidence.PinnedArtifact,
                        "revalidate",
                        autospec=True,
                        side_effect=mutate_mid_revalidation,
                    ),
                    mock.patch.object(
                        producer, "current_head_sha", return_value=self.source_sha
                    ),
                    mock.patch.object(
                        producer, "current_source_sha", return_value=self.source_sha
                    ),
                ):
                    status = producer.main(
                        [
                            "--output",
                            str(output),
                            "--raw-manifest",
                            str(manifest_path),
                            "--app-apk",
                            str(app_apk),
                            "--test-apk",
                            str(test_apk),
                        ]
                    )
                self.assertEqual(status, 1)
                results = json.loads(output.read_text())
                self.assertNotIn("rawBundleProvenance", results)
                self.assertTrue(
                    all(
                        value["state"] == "FAIL" and expected_code in value["reason"]
                        for value in results["gateResults"].values()
                    )
                )

    def test_raw_mutation_before_pending_write_replaces_forged_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                directory
            )
            artifact_root = Path(manifest["artifactRoot"])
            output_parent = directory / "output"
            output_parent.mkdir(mode=0o700)
            output = output_parent / "results.json"
            output.write_text('{"gateResults":{"forged":"PASS"}}')
            output.chmod(0o600)

            def mutate_after_output_reservation() -> str:
                extra = artifact_root / "caller-summary.json"
                extra.write_bytes(b"caller summary")
                extra.chmod(0o600)
                return self.source_sha

            with mock.patch.object(
                producer,
                "current_head_sha",
                side_effect=mutate_after_output_reservation,
            ):
                status = producer.main(
                    [
                        "--output",
                        str(output),
                        "--raw-manifest",
                        str(manifest_path),
                        "--app-apk",
                        str(app_apk),
                        "--test-apk",
                        str(test_apk),
                    ]
                )
            self.assertEqual(status, 1)
            results = json.loads(output.read_text())
            self.assertNotIn("rawBundleProvenance", results)
            self.assertTrue(
                all(
                    value["state"] == "FAIL" and "INVENTORY_MISMATCH" in value["reason"]
                    for value in results["gateResults"].values()
                )
            )

    def test_apk_hashing_streams_without_whole_file_helper(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            apk = Path(directory_name) / "large.apk"
            payload = b"apk-chunk" * (1024 * 1024)
            apk.write_bytes(payload)
            with mock.patch.object(
                raw_evidence,
                "read_regular_file",
                side_effect=AssertionError("whole-file helper must not hash APKs"),
            ):
                digest = raw_evidence.sha256_file(apk, "app APK")
            self.assertEqual(digest, hashlib.sha256(payload).hexdigest())

    def test_raw_manifest_must_be_private_canonical_and_unaliased(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                directory
            )
            manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
            manifest_path.chmod(0o600)
            with self.assertRaisesRegex(ValueError, "MANIFEST_NONCANONICAL"):
                raw_evidence.validate_raw_bundle(
                    manifest_path,
                    expected_source_sha=self.source_sha,
                    app_apk=app_apk,
                    test_apk=test_apk,
                )
            self.rewrite_manifest(manifest_path, manifest)
            manifest_path.chmod(0o644)
            with self.assertRaisesRegex(ValueError, "PRIVACY_INVALID"):
                raw_evidence.validate_raw_bundle(
                    manifest_path,
                    expected_source_sha=self.source_sha,
                    app_apk=app_apk,
                    test_apk=test_apk,
                )
            manifest_path.chmod(0o600)
            alias = directory / "manifest-alias.json"
            os.link(manifest_path, alias)
            with self.assertRaisesRegex(ValueError, "one hard link"):
                raw_evidence.validate_raw_bundle(
                    manifest_path,
                    expected_source_sha=self.source_sha,
                    app_apk=app_apk,
                    test_apk=test_apk,
                )

    def test_raw_manifest_symlink_and_artifact_symlink_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                directory
            )
            symlink_manifest = directory / "manifest-symlink.json"
            symlink_manifest.symlink_to(manifest_path)
            with self.assertRaisesRegex(ValueError, "PATH_INVALID"):
                raw_evidence.validate_raw_bundle(
                    symlink_manifest,
                    expected_source_sha=self.source_sha,
                    app_apk=app_apk,
                    test_apk=test_apk,
                )
            artifact = manifest["actions"][0]["artifacts"][0]
            artifact_path = Path(manifest["artifactRoot"]) / artifact["path"]
            target = directory / "moved.raw"
            artifact_path.rename(target)
            artifact_path.symlink_to(target)
            with self.assertRaisesRegex(ValueError, "ARTIFACT_MISSING"):
                raw_evidence.validate_raw_bundle(
                    manifest_path,
                    expected_source_sha=self.source_sha,
                    app_apk=app_apk,
                    test_apk=test_apk,
                )

    def test_artifact_root_and_files_must_remain_private_and_unaliased(self) -> None:
        mutations = ("root-mode", "root-symlink", "artifact-mode", "artifact-link")
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with tempfile.TemporaryDirectory() as directory_name:
                    directory = Path(directory_name)
                    manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                        directory
                    )
                    artifact = manifest["actions"][0]["artifacts"][0]
                    artifact_path = Path(manifest["artifactRoot"]) / artifact["path"]
                    if mutation == "root-mode":
                        Path(manifest["artifactRoot"]).chmod(0o755)
                        expected = "PRIVACY_INVALID"
                    elif mutation == "root-symlink":
                        alias = directory / "artifact-root-alias"
                        alias.symlink_to(
                            Path(manifest["artifactRoot"]), target_is_directory=True
                        )
                        manifest["artifactRoot"] = str(alias)
                        self.rewrite_manifest(manifest_path, manifest)
                        expected = "PATH_INVALID"
                    elif mutation == "artifact-mode":
                        artifact_path.chmod(0o644)
                        expected = "PRIVACY_INVALID"
                    else:
                        os.link(artifact_path, directory / "artifact-hardlink.raw")
                        expected = "PATH_INVALID"
                    with self.assertRaisesRegex(ValueError, expected):
                        raw_evidence.validate_raw_bundle(
                            manifest_path,
                            expected_source_sha=self.source_sha,
                            app_apk=app_apk,
                            test_apk=test_apk,
                        )

    def test_duplicate_manifest_keys_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory_name:
            directory = Path(directory_name)
            manifest_path, app_apk, test_apk, _ = self.create_raw_bundle(directory)
            manifest_path.write_text(
                '{"version":"first","version":"second"}\n', encoding="utf-8"
            )
            manifest_path.chmod(0o600)
            with self.assertRaisesRegex(ValueError, "duplicate key"):
                raw_evidence.validate_raw_bundle(
                    manifest_path,
                    expected_source_sha=self.source_sha,
                    app_apk=app_apk,
                    test_apk=test_apk,
                )

    def test_raw_bundle_rejects_digest_size_tamper_and_missing_artifact(self) -> None:
        mutations = ("digest", "size", "missing")
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with tempfile.TemporaryDirectory() as directory_name:
                    directory = Path(directory_name)
                    manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                        directory
                    )
                    artifact = manifest["actions"][0]["artifacts"][0]
                    artifact_path = Path(manifest["artifactRoot"]) / artifact["path"]
                    if mutation == "digest":
                        artifact["sha256"] = "f" * 64
                        expected = "DIGEST_MISMATCH"
                    elif mutation == "size":
                        artifact["sizeBytes"] += 1
                        expected = "SIZE_MISMATCH"
                    else:
                        artifact_path.unlink()
                        expected = "ARTIFACT_MISSING"
                    self.rewrite_manifest(manifest_path, manifest)
                    with self.assertRaisesRegex(ValueError, expected):
                        raw_evidence.validate_raw_bundle(
                            manifest_path,
                            expected_source_sha=self.source_sha,
                            app_apk=app_apk,
                            test_apk=test_apk,
                        )

    def test_raw_bundle_rejects_partial_extra_or_reordered_inventory(self) -> None:
        mutations = ("partial", "extra", "reordered", "extra-file")
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with tempfile.TemporaryDirectory() as directory_name:
                    directory = Path(directory_name)
                    manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                        directory
                    )
                    if mutation == "partial":
                        manifest["actions"].pop()
                    elif mutation == "extra":
                        manifest["actions"].append(dict(manifest["actions"][-1]))
                    elif mutation == "reordered":
                        manifest["actions"][0], manifest["actions"][1] = (
                            manifest["actions"][1],
                            manifest["actions"][0],
                        )
                    else:
                        extra = Path(manifest["artifactRoot"]) / "caller-summary.json"
                        extra.write_text('{"state":"PASS"}\n', encoding="utf-8")
                        extra.chmod(0o600)
                    self.rewrite_manifest(manifest_path, manifest)
                    with self.assertRaisesRegex(ValueError, "INVENTORY_MISMATCH"):
                        raw_evidence.validate_raw_bundle(
                            manifest_path,
                            expected_source_sha=self.source_sha,
                            app_apk=app_apk,
                            test_apk=test_apk,
                        )

    def test_raw_bundle_rejects_stale_source_and_wrong_apk_bindings(self) -> None:
        mutations = ("source", "app-digest", "same-apk-digest")
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with tempfile.TemporaryDirectory() as directory_name:
                    directory = Path(directory_name)
                    manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                        directory
                    )
                    if mutation == "source":
                        manifest["sourceSha"] = "b" * 40
                        expected = "SOURCE_MISMATCH"
                    elif mutation == "app-digest":
                        manifest["appApkSha256"] = "f" * 64
                        expected = "APK_DIGEST_MISMATCH"
                    else:
                        manifest["testApkSha256"] = manifest["appApkSha256"]
                        expected = "APK_BINDING_INVALID"
                    self.rewrite_manifest(manifest_path, manifest)
                    with self.assertRaisesRegex(ValueError, expected):
                        raw_evidence.validate_raw_bundle(
                            manifest_path,
                            expected_source_sha=self.source_sha,
                            app_apk=app_apk,
                            test_apk=test_apk,
                        )

    def test_raw_bundle_rejects_stale_or_mixed_run_metadata(self) -> None:
        mutations = ("stale", "stale-window", "correlation", "window", "vantage")
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                with tempfile.TemporaryDirectory() as directory_name:
                    directory = Path(directory_name)
                    manifest_path, app_apk, test_apk, manifest = self.create_raw_bundle(
                        directory
                    )
                    if mutation == "stale":
                        delta = raw_evidence.MAX_EVIDENCE_AGE_MS + 1
                        manifest["createdAtEpochMs"] -= delta
                        for action in manifest["actions"]:
                            action["windowStartedAtEpochMs"] -= delta
                            action["windowFinishedAtEpochMs"] -= delta
                            for artifact in action["artifacts"]:
                                artifact["windowStartedAtEpochMs"] -= delta
                                artifact["windowFinishedAtEpochMs"] -= delta
                        expected = "EVIDENCE_STALE"
                    elif mutation == "stale-window":
                        delta = raw_evidence.MAX_EVIDENCE_AGE_MS + 1
                        action = manifest["actions"][0]
                        action["windowStartedAtEpochMs"] -= delta
                        action["windowFinishedAtEpochMs"] -= delta
                        for artifact in action["artifacts"]:
                            artifact["windowStartedAtEpochMs"] -= delta
                            artifact["windowFinishedAtEpochMs"] -= delta
                        expected = "EVIDENCE_STALE"
                    elif mutation == "correlation":
                        manifest["actions"][1]["correlationId"] = manifest["actions"][
                            0
                        ]["correlationId"]
                        expected = "CORRELATION_MISMATCH"
                    elif mutation == "window":
                        manifest["actions"][0]["artifacts"][0][
                            "windowFinishedAtEpochMs"
                        ] += 1
                        expected = "WINDOW_MISMATCH"
                    else:
                        manifest["actions"][0]["artifacts"][0]["vantage"] = (
                            "caller-summary"
                        )
                        expected = "VANTAGE_MISMATCH"
                    self.rewrite_manifest(manifest_path, manifest)
                    with self.assertRaisesRegex(ValueError, expected):
                        raw_evidence.validate_raw_bundle(
                            manifest_path,
                            expected_source_sha=self.source_sha,
                            app_apk=app_apk,
                            test_apk=test_apk,
                        )

    def test_checker_rejects_object_pass_even_with_forged_metadata(self) -> None:
        results = self.results()
        results["gateResults"][producer.ORDINARY_GATE_IDS[0]] = {"state": "PASS"}
        results["producerAttestation"] = {
            "forged": "all public hashes and arbitrary artifacts"
        }
        with self.assertRaisesRegex(ValueError, producer.UNAVAILABLE_CODE):
            self.validate(results)

    def test_checker_rejects_legacy_string_pass(self) -> None:
        results = self.results()
        results["gateResults"] = {
            gate_id: "PASS" for gate_id in producer.ORDINARY_GATE_IDS
        }
        with self.assertRaisesRegex(ValueError, producer.UNAVAILABLE_CODE):
            self.validate(results)

    def test_checker_rejects_ordinary_pass_inside_full_policy_document(self) -> None:
        policy = gates.load_json(gates.POLICY_PATH)
        results = {
            "appliesTo": "android-client-release",
            "gateResults": {gate["id"]: "PASS" for gate in policy["gates"]},
            "sourceSha": self.source_sha,
            "version": producer.RESULTS_VERSION,
        }
        with self.assertRaisesRegex(ValueError, producer.UNAVAILABLE_CODE):
            self.validate(results)

        results = self.results()
        results["gateResults"][producer.ORDINARY_GATE_IDS[0]] = {"state": "PASS"}
        results["gateResults"]["unexpected-extra-gate"] = {
            "reason": "extra",
            "state": "FAIL",
        }
        with self.assertRaisesRegex(ValueError, "exactly cover"):
            self.validate(results)

    def test_checker_rejects_partial_or_non_structured_failure(self) -> None:
        results = self.results()
        results["gateResults"].pop(producer.ORDINARY_GATE_IDS[-1])
        with self.assertRaisesRegex(ValueError, "exactly cover"):
            self.validate(results)

        results = self.results()
        results["gateResults"][producer.ORDINARY_GATE_IDS[0]] = "FAIL"
        with self.assertRaisesRegex(ValueError, "structured all-FAIL"):
            self.validate(results)

    def test_current_source_rejects_untracked_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            subprocess.run(["git", "init", "-q"], cwd=repository, check=True)
            subprocess.run(
                ["git", "config", "user.email", "test@example.invalid"],
                cwd=repository,
                check=True,
            )
            subprocess.run(
                ["git", "config", "user.name", "Test"], cwd=repository, check=True
            )
            (repository / "tracked").write_text("tracked\n", encoding="utf-8")
            subprocess.run(["git", "add", "tracked"], cwd=repository, check=True)
            subprocess.run(["git", "commit", "-qm", "test"], cwd=repository, check=True)
            self.assertRegex(producer.current_source_sha(repository), r"^[0-9a-f]{40}$")
            (repository / "untracked").write_text("dirty\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "clean source checkout"):
                producer.current_source_sha(repository)

    def test_cli_replaces_stale_pass_with_complete_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "results.json"
            output.write_text('{"stale":"PASS"}\n', encoding="utf-8")
            with (
                mock.patch.object(
                    producer, "current_head_sha", return_value=self.source_sha
                ),
                mock.patch.object(
                    producer, "current_source_sha", return_value=self.source_sha
                ),
            ):
                status = producer.main(["--output", str(output)])
            self.assertEqual(status, 1)
            results = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(self.validate(results), results)
            self.assertTrue(
                all(
                    "RAW_EVIDENCE_REQUIRED" in value["reason"]
                    for value in results["gateResults"].values()
                )
            )

    def test_cli_replaces_stale_pass_on_dirty_or_unexpected_source_failure(
        self,
    ) -> None:
        failures = (
            producer.EvidenceError("SOURCE_DIRTY", "dirty checkout"),
            RuntimeError("unexpected"),
        )
        for failure in failures:
            with self.subTest(failure=type(failure).__name__):
                with tempfile.TemporaryDirectory() as directory:
                    output = Path(directory) / "results.json"
                    output.write_text('{"stale":"PASS"}\n', encoding="utf-8")
                    with (
                        mock.patch.object(
                            producer,
                            "current_head_sha",
                            return_value=self.source_sha,
                        ),
                        mock.patch.object(
                            producer, "current_source_sha", side_effect=failure
                        ),
                    ):
                        status = producer.main(["--output", str(output)])
                    self.assertEqual(status, 1)
                    results = json.loads(output.read_text(encoding="utf-8"))
                    self.assertEqual(
                        output.read_bytes(), producer.canonical_json_bytes(results)
                    )
                    self.assertEqual(output.stat().st_mode & 0o777, 0o600)
                    self.assertEqual(self.validate(results), results)
                    self.assertNotIn("stale", results)
                    self.assertTrue(
                        all(
                            value["state"] == "FAIL"
                            for value in results["gateResults"].values()
                        )
                    )

    def test_cli_fails_closed_when_head_changes_during_validation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "results.json"
            with (
                mock.patch.object(
                    producer, "current_head_sha", return_value=self.source_sha
                ),
                mock.patch.object(
                    producer, "current_source_sha", return_value="b" * 40
                ),
            ):
                status = producer.main(["--output", str(output)])
            self.assertEqual(status, 1)
            results = json.loads(output.read_text(encoding="utf-8"))
            self.assertTrue(
                all(
                    "SOURCE_CHANGED" in value["reason"]
                    for value in results["gateResults"].values()
                )
            )

    def test_cli_replaces_stale_pass_when_head_cannot_be_bound(self) -> None:
        failures = (
            producer.EvidenceError("SOURCE_INVALID", "invalid head"),
            RuntimeError("unexpected head lookup failure"),
        )
        for failure in failures:
            with self.subTest(failure=type(failure).__name__):
                with tempfile.TemporaryDirectory() as directory:
                    output = Path(directory) / "results.json"
                    output.write_text('{"stale":"PASS"}\n', encoding="utf-8")
                    with mock.patch.object(
                        producer,
                        "current_head_sha",
                        side_effect=failure,
                    ):
                        status = producer.main(["--output", str(output)])
                    self.assertEqual(status, 2)
                    results = json.loads(output.read_text(encoding="utf-8"))
                    self.assertEqual(
                        output.read_bytes(), producer.canonical_json_bytes(results)
                    )
                    self.assertEqual(output.stat().st_mode & 0o777, 0o600)
                    self.assertEqual(
                        set(results["gateResults"]), set(producer.ORDINARY_GATE_IDS)
                    )
                    self.assertEqual(results["sourceSha"], producer.UNKNOWN_SOURCE_SHA)
                    expected_code = (
                        "SOURCE_INVALID"
                        if isinstance(failure, producer.EvidenceError)
                        else "SOURCE_BINDING_FAILED"
                    )
                    self.assertTrue(
                        all(
                            value["state"] == "FAIL"
                            and expected_code in value["reason"]
                            for value in results["gateResults"].values()
                        )
                    )


if __name__ == "__main__":
    unittest.main()
