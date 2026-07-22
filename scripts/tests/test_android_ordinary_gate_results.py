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
            load_artifact_root = raw_evidence.load_artifact_root_for_output

            def move_app_during_manifest_load(path: Path) -> Path:
                artifact_root = load_artifact_root(path)
                app_apk.rename(output)
                return artifact_root

            with mock.patch.object(
                raw_evidence,
                "load_artifact_root_for_output",
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
