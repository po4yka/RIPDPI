#!/usr/bin/env python3

from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from datetime import UTC, datetime
from pathlib import Path

from scripts.ci.release_preflight import (
    PreflightError,
    _ensure_remote_tag_absent,
    run_preflight,
)


ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "quality/release-gates/release-contract.json"


class ReleasePreflightTest(unittest.TestCase):
    def make_repo(self, root: Path) -> tuple[Path, str, str]:
        repo = root / "repo"
        repo.mkdir()
        subprocess.run(["git", "init", "-q", "-b", "main"], cwd=repo, check=True)
        subprocess.run(["git", "config", "user.name", "Test"], cwd=repo, check=True)
        subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=repo, check=True)
        (repo / "state.txt").write_text("base\n", encoding="utf-8")
        subprocess.run(["git", "add", "state.txt"], cwd=repo, check=True)
        subprocess.run(["git", "commit", "-q", "-m", "chore(release): cut window"], cwd=repo, check=True)
        subprocess.run(["git", "tag", "v0.1.4"], cwd=repo, check=True)
        record = repo / "quality/release-gates/release-windows/v0.1.5.json"
        record.parent.mkdir(parents=True)
        record.write_text(
            '{\n  "version": "ripdpi_release_window_cut_v1",\n'
            '  "releaseTag": "v0.1.5",\n  "startedAt": "2026-08-03T00:00:00Z"\n}\n',
            encoding="utf-8",
        )
        subprocess.run(["git", "add", str(record.relative_to(repo))], cwd=repo, check=True)
        subprocess.run(
            ["git", "commit", "-q", "-m", "chore(release): record v0.1.5 cut"],
            cwd=repo,
            check=True,
        )
        start = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
        (repo / "state.txt").write_text("base\nfix\n", encoding="utf-8")
        subprocess.run(["git", "add", "state.txt"], cwd=repo, check=True)
        subprocess.run(["git", "commit", "-q", "-m", "fix(release): prepare candidate"], cwd=repo, check=True)
        candidate = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
        return repo, start, candidate

    def test_pass_receipt_records_executed_checks_and_non_substitution_limits(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            repo, start, candidate = self.make_repo(Path(raw))
            observed: list[tuple[str, ...]] = []

            def successful(command: list[str], cwd: Path) -> None:
                self.assertEqual(repo.resolve(), cwd)
                observed.append(tuple(command))

            receipt = run_preflight(
                repo=repo,
                contract_path=CONTRACT,
                release_tag="v0.1.5",
                window_start_sha=start,
                window_started_at=datetime(2026, 8, 3, tzinfo=UTC),
                now=datetime(2026, 8, 4, tzinfo=UTC),
                command_runner=successful,
                candidate_runs=[{
                    "ref": "refs/tags/release-candidates/v0.1.5/run-123",
                    "sha": start,
                }],
                remote_tag_checker=lambda repo, tag: None,
            )
            self.assertEqual("pass", receipt["status"])
            self.assertEqual(candidate, receipt["sourceSha"])
            self.assertEqual("v0.1.4", receipt["baseTag"])
            self.assertGreaterEqual(len(observed), 7)
            flattened = "\n".join(" ".join(command) for command in observed)
            self.assertIn(":app:assembleGithubFullReleaseAndroidTest", flattened)
            self.assertRegex(flattened, r"ripdpi\.nativeAbisOverride=(arm64-v8a|x86_64)")
            self.assertRegex(
                flattened,
                r"verify-local-release-preflight-output\.sh (arm64-v8a|x86_64)",
            )
            for task in (
                ":app:assembleFdroidFullRelease",
                ":app:assembleFdroidSimpleRelease",
                ":app:assembleGithubFullRelease",
                ":app:assembleGithubSimpleRelease",
                ":app:assemblePlayFullRelease",
                ":app:assemblePlaySimpleRelease",
                ":app:bundlePlayFullRelease",
            ):
                self.assertIn(task, flattened)
            self.assertIn("check_release_contract.py", flattened)
            self.assertIn("test_release_artifact_uploads", flattened)
            self.assertIn("test_release_candidate_manifest", flattened)
            self.assertIn("test_evidence_retention", flattened)
            self.assertEqual(1, receipt["window"]["candidateRunCount"])
            self.assertEqual(
                [
                    "does-not-sign-artifacts",
                    "does-not-replace-exact-sha-hosted-ci",
                    "host-abi-only",
                ],
                receipt["limitations"],
            )

    def test_failure_stops_without_pass_receipt(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            repo, start, _ = self.make_repo(Path(raw))

            def failing(command: list[str], cwd: Path) -> None:
                raise subprocess.CalledProcessError(1, command)

            with self.assertRaisesRegex(PreflightError, "failed"):
                run_preflight(
                    repo, CONTRACT, "v0.1.5", start,
                    datetime(2026, 8, 3, tzinfo=UTC), datetime(2026, 8, 4, tzinfo=UTC),
                    failing, [], lambda repo, tag: None,
                )

    def test_existing_target_tag_fails_before_any_check_runs(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            repo, start, _ = self.make_repo(Path(raw))
            subprocess.run(["git", "tag", "v0.1.5"], cwd=repo, check=True)
            observed: list[list[str]] = []
            with self.assertRaisesRegex(PreflightError, "already exists"):
                run_preflight(
                    repo, CONTRACT, "v0.1.5", start,
                    datetime(2026, 8, 3, tzinfo=UTC), datetime(2026, 8, 4, tzinfo=UTC),
                    lambda command, cwd: observed.append(command), [],
                )
            self.assertEqual([], observed)

    def test_recipe_and_ci_contract_expose_only_full_preflight(self) -> None:
        justfile = (ROOT / "justfile").read_text(encoding="utf-8")
        ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn("release-preflight tag window_start started_at", justfile)
        self.assertIn("scripts/ci/release_preflight.py", justfile)
        self.assertNotIn("skip_build", justfile)
        self.assertIn("scripts.tests.test_release_preflight", ci)

    def test_remote_only_target_tag_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            repo, _, _ = self.make_repo(root)
            remote = root / "remote.git"
            subprocess.run(["git", "init", "-q", "--bare", remote], check=True)
            subprocess.run(["git", "remote", "add", "origin", str(remote)], cwd=repo, check=True)
            subprocess.run(["git", "tag", "v0.1.5"], cwd=repo, check=True)
            subprocess.run(["git", "push", "-q", "origin", "v0.1.5"], cwd=repo, check=True)
            subprocess.run(["git", "tag", "-d", "v0.1.5"], cwd=repo, check=True, capture_output=True)
            with self.assertRaisesRegex(PreflightError, "already exists remotely"):
                _ensure_remote_tag_absent(repo, "v0.1.5")

    def test_default_preflight_loads_complete_remote_candidate_history(self) -> None:
        source = (ROOT / "scripts/ci/release_preflight.py").read_text(encoding="utf-8")
        self.assertIn('"git", "ls-remote", "--refs", "--tags", "origin"', source)
        self.assertNotIn("candidate_runs=[]", source)

    def test_output_verifier_selects_production_native_libs_exactly(self) -> None:
        source = (ROOT / "scripts/ci/verify-local-release-preflight-output.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn(
            'lib_dir="app/build/intermediates/merged_native_libs/'
            'githubFullRelease/mergeGithubFullReleaseNativeLibs/out/lib"',
            source,
        )
        self.assertNotIn("*githubfullrelease*/out/lib", source)
        self.assertIn('owned_libraries=("$abi_dir"/libripdpi*.so)', source)
        self.assertNotIn("xargs", source)
        self.assertIn('--abis "$expected_abi"', source)

    def make_release_output_fixture(self, root: Path, native_abis: dict[str, list[str]]) -> None:
        for variant in (
            "fdroidFull",
            "fdroidSimple",
            "githubFull",
            "githubSimple",
            "playFull",
            "playSimple",
        ):
            output = root / "app/build/outputs/apk" / variant / "release" / f"{variant}.apk"
            output.parent.mkdir(parents=True, exist_ok=True)
            output.touch()
        bundle = root / "app/build/outputs/bundle/playFullRelease/app.aab"
        bundle.parent.mkdir(parents=True, exist_ok=True)
        bundle.touch()
        test_apk = root / "app/build/outputs/apk/androidTest/github-full-release-test.apk"
        test_apk.parent.mkdir(parents=True, exist_ok=True)
        test_apk.touch()
        lib_root = (
            root
            / "app/build/intermediates/merged_native_libs/githubFullRelease/"
            "mergeGithubFullReleaseNativeLibs/out/lib"
        )
        for abi, libraries in native_abis.items():
            for library in libraries:
                output = lib_root / abi / library
                output.parent.mkdir(parents=True, exist_ok=True)
                output.touch()
        scripts = root / "scripts/ci"
        scripts.mkdir(parents=True)
        for name in ("verify_native_elfs.py", "verify_jni_readiness_mapping.py"):
            (scripts / name).write_text("raise SystemExit(0)\n", encoding="utf-8")
        shutil.copy2(
            ROOT / "scripts/ci/verify-local-release-preflight-output.sh",
            scripts / "verify-local-release-preflight-output.sh",
        )

    def run_output_verifier(self, root: Path, expected_abi: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "bash",
                str(root / "scripts/ci/verify-local-release-preflight-output.sh"),
                expected_abi,
            ],
            text=True,
            capture_output=True,
            check=False,
        )

    def test_output_verifier_accepts_exact_host_native_abi(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            self.make_release_output_fixture(root, {"arm64-v8a": ["libripdpi.so"]})
            self.assertEqual(0, self.run_output_verifier(root, "arm64-v8a").returncode)

    def test_output_verifier_rejects_wrong_extra_and_missing_host_native_abi(self) -> None:
        cases = {
            "wrong": {"x86_64": ["libripdpi.so"]},
            "extra-partial": {
                "arm64-v8a": ["libripdpi.so"],
                "x86_64": ["libripdpi-relay.so"],
            },
            "missing-main": {"arm64-v8a": ["libripdpi-relay.so"]},
        }
        for label, native_abis in cases.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as raw:
                root = Path(raw)
                self.make_release_output_fixture(root, native_abis)
                self.assertNotEqual(0, self.run_output_verifier(root, "arm64-v8a").returncode)


if __name__ == "__main__":
    unittest.main()
