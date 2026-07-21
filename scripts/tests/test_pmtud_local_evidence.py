from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts/ci/pmtud_local_evidence.py"
SPEC = importlib.util.spec_from_file_location("pmtud_local_evidence", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
evidence = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = evidence
SPEC.loader.exec_module(evidence)


SOURCE_SHA = "1" * 40
NOW = datetime(2026, 7, 22, 10, 10, tzinfo=timezone.utc)


class PmtudLocalEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tempdir = tempfile.TemporaryDirectory()
        self.root = Path(self.tempdir.name)
        self.artifact_dir = self.root / "artifacts"
        self.artifact_dir.mkdir()

    def tearDown(self) -> None:
        self.tempdir.cleanup()

    def write_pass_manifest(self) -> Path:
        cases = []
        for case in evidence.REQUIRED_CASES:
            artifact = {
                "caseId": case.case_id,
                "failed": 0,
                "failureCode": "NONE",
                "ignored": 0,
                "measured": 0,
                "passed": 1,
                "result": "PASS",
                "sourceSha": SOURCE_SHA,
                "testName": case.test_name,
                "version": evidence.ARTIFACT_VERSION,
            }
            path = self.artifact_dir / f"{case.case_id}.json"
            path.write_bytes(evidence.canonical_json_bytes(artifact))
            cases.append(
                {
                    "artifact": path.name,
                    "artifactSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                    "id": case.case_id,
                    "package": case.package,
                    "result": "PASS",
                    "target": case.target,
                    "testName": case.test_name,
                }
            )
        manifest = {
            "artifacts": cases,
            "completedAt": "2026-07-22T10:10:00Z",
            "environment": {
                "architecture": "arm64",
                "cargoVersion": "cargo 1.88.0 (873a06493 2025-05-10)",
                "operatingSystem": "darwin",
                "rustcVersion": "rustc 1.88.0 (6b00bc388 2025-06-23)",
            },
            "provenance": {
                "cargoLockSha256": "2" * 64,
                "runnerSha256": "3" * 64,
                "snapshotMethod": "git-archive",
                "suiteDefinitionSha256": "5" * 64,
                "validatorSha256": "4" * 64,
            },
            "result": "PASS",
            "sourceSha": SOURCE_SHA,
            "startedAt": "2026-07-22T10:00:00Z",
            "suite": evidence.SUITE,
            "validUntil": "2026-07-22T16:00:00Z",
            "version": evidence.MANIFEST_VERSION,
        }
        path = self.root / "manifest.json"
        path.write_bytes(evidence.canonical_json_bytes(manifest))
        return path

    def validate(self, path: Path, *, require_pass: bool = True) -> dict[str, object]:
        return evidence.validate_manifest(
            path,
            artifact_dir=self.artifact_dir,
            expected_source_sha=SOURCE_SHA,
            now=NOW,
            require_pass=require_pass,
            _verify_source_provenance=False,
        )

    def rewrite(self, path: Path, mutate) -> None:
        value = json.loads(path.read_text())
        mutate(value)
        path.write_bytes(evidence.canonical_json_bytes(value))

    def test_accepts_complete_exact_sha_pass(self) -> None:
        manifest = self.validate(self.write_pass_manifest())
        self.assertEqual(manifest["result"], "PASS")
        self.assertEqual(len(manifest["artifacts"]), len(evidence.REQUIRED_CASES))

    def test_rejects_missing_extra_duplicate_and_reordered_cases(self) -> None:
        mutations = (
            lambda value: value["artifacts"].pop(),
            lambda value: value["artifacts"].append(
                dict(value["artifacts"][0], id="fake")
            ),
            lambda value: value["artifacts"].__setitem__(
                1, dict(value["artifacts"][0])
            ),
            lambda value: value["artifacts"].reverse(),
        )
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                path = self.write_pass_manifest()
                self.rewrite(path, mutate)
                with self.assertRaisesRegex(ValueError, "required case sequence"):
                    self.validate(path)

    def test_rejects_skip_fake_partial_and_non_pass(self) -> None:
        for result in ("SKIP", "FAKE", "PARTIAL", "FAIL", "INCONCLUSIVE"):
            with self.subTest(result=result):
                path = self.write_pass_manifest()
                self.rewrite(
                    path, lambda value: value["artifacts"][0].update(result=result)
                )
                with self.assertRaises(ValueError):
                    self.validate(path)

    def test_rejects_stale_future_and_overlong_windows(self) -> None:
        mutations = (
            lambda value: value.update(validUntil="2026-07-22T09:59:59Z"),
            lambda value: value.update(startedAt="2026-07-22T10:16:00Z"),
            lambda value: value.update(
                completedAt="2026-07-22T17:00:01Z", validUntil="2026-07-22T17:01:00Z"
            ),
        )
        for mutate in mutations:
            path = self.write_pass_manifest()
            self.rewrite(path, mutate)
            with self.assertRaises(ValueError):
                self.validate(path)

    def test_rejects_digest_tamper_and_unlisted_artifact(self) -> None:
        path = self.write_pass_manifest()
        first = self.artifact_dir / f"{evidence.REQUIRED_CASES[0].case_id}.json"
        first.write_text("tampered\n")
        with self.assertRaisesRegex(ValueError, "digest"):
            self.validate(path)

        path = self.write_pass_manifest()
        (self.artifact_dir / "unlisted.json").write_text("{}\n")
        with self.assertRaisesRegex(ValueError, "artifact set"):
            self.validate(path)

    def test_rejects_source_mismatch_noncanonical_and_sensitive_environment(
        self,
    ) -> None:
        path = self.write_pass_manifest()
        with self.assertRaisesRegex(ValueError, "source SHA"):
            evidence.validate_manifest(
                path,
                artifact_dir=self.artifact_dir,
                expected_source_sha="a" * 40,
                now=NOW,
                require_pass=True,
                _verify_source_provenance=False,
            )

        path.write_text(json.dumps(json.loads(path.read_text()), indent=2))
        with self.assertRaisesRegex(ValueError, "canonical"):
            self.validate(path)

        for field, leaked in (
            ("hostname", "developer-mac"),
            ("home", "/Users/alice"),
            ("token", "secret"),
        ):
            path = self.write_pass_manifest()
            self.rewrite(
                path,
                lambda value, field=field, leaked=leaked: value["environment"].update(
                    {field: leaked}
                ),
            )
            with self.assertRaisesRegex(ValueError, "environment fields"):
                self.validate(path)

    def test_source_provenance_digests_are_verified_not_just_well_formed(self) -> None:
        path = self.write_pass_manifest()
        blobs = {
            evidence.LOCK_RELATIVE: b"locked-dependencies\n",
            evidence.RUNNER_RELATIVE: (
                evidence.ROOT / evidence.RUNNER_RELATIVE
            ).read_bytes(),
            evidence.VALIDATOR_RELATIVE: Path(evidence.__file__).read_bytes(),
        }

        def bind_provenance(value) -> None:
            value["provenance"] = {
                "cargoLockSha256": hashlib.sha256(
                    blobs[evidence.LOCK_RELATIVE]
                ).hexdigest(),
                "runnerSha256": hashlib.sha256(
                    blobs[evidence.RUNNER_RELATIVE]
                ).hexdigest(),
                "snapshotMethod": "git-archive",
                "suiteDefinitionSha256": evidence.suite_definition_sha256(),
                "validatorSha256": hashlib.sha256(
                    blobs[evidence.VALIDATOR_RELATIVE]
                ).hexdigest(),
            }

        self.rewrite(path, bind_provenance)
        with mock.patch.object(
            evidence,
            "source_blob",
            side_effect=lambda _repo, _sha, relative: blobs[relative],
        ):
            evidence.validate_manifest(
                path,
                artifact_dir=self.artifact_dir,
                expected_source_sha=SOURCE_SHA,
                now=NOW,
                require_pass=True,
            )
            self.rewrite(
                path, lambda value: value["provenance"].update(cargoLockSha256="f" * 64)
            )
            with self.assertRaisesRegex(ValueError, "exact source commit"):
                evidence.validate_manifest(
                    path,
                    artifact_dir=self.artifact_dir,
                    expected_source_sha=SOURCE_SHA,
                    now=NOW,
                    require_pass=True,
                )

    def test_parse_test_summary_requires_exactly_one_real_test(self) -> None:
        good = "test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 17 filtered out"
        self.assertEqual(evidence.parse_test_summary(good), (1, 0, 0, 0))
        for bad in (
            "test result: ok. 0 passed; 0 failed; 1 ignored; 0 measured; 17 filtered out",
            "test result: ok. 0 passed; 0 failed; 0 ignored; 0 measured; 18 filtered out",
            "test result: FAILED. 0 passed; 1 failed; 0 ignored; 0 measured; 17 filtered out",
            "fake success",
        ):
            with self.subTest(output=bad):
                with self.assertRaises(ValueError):
                    evidence.parse_test_summary(bad)

    def test_failure_manifest_is_structural_but_never_a_pass_gate(self) -> None:
        path = self.write_pass_manifest()
        first_case = evidence.REQUIRED_CASES[0]
        artifact_path = self.artifact_dir / f"{first_case.case_id}.json"
        artifact = json.loads(artifact_path.read_text())
        artifact.update(failed=1, failureCode="COMMAND_FAILED", passed=0, result="FAIL")
        artifact_path.write_bytes(evidence.canonical_json_bytes(artifact))

        def fail_first(value) -> None:
            value["result"] = "FAIL"
            value["artifacts"][0]["result"] = "FAIL"
            value["artifacts"][0]["artifactSha256"] = hashlib.sha256(
                artifact_path.read_bytes()
            ).hexdigest()

        self.rewrite(path, fail_first)
        manifest = self.validate(path, require_pass=False)
        self.assertEqual(manifest["result"], "FAIL")
        with self.assertRaisesRegex(ValueError, "PASS required"):
            self.validate(path, require_pass=True)

    def test_public_validate_command_always_requires_pass(self) -> None:
        with mock.patch.object(evidence, "validate_manifest") as validate:
            result = evidence.main(
                [
                    "validate",
                    "--manifest",
                    "/tmp/manifest.json",
                    "--artifact-dir",
                    "/tmp/artifacts",
                    "--source-sha",
                    SOURCE_SHA,
                ]
            )
        self.assertEqual(result, 0)
        self.assertTrue(validate.call_args.kwargs["require_pass"])

    def test_validity_window_constant_is_bounded(self) -> None:
        self.assertLessEqual(evidence.MAX_VALIDITY, timedelta(hours=6))


if __name__ == "__main__":
    unittest.main()
