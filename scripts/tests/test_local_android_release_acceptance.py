from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/ci/approve_local_android_release_evidence.py"


class LocalAndroidReleaseAcceptanceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name) / "repo"
        (self.root / "scripts/ci").mkdir(parents=True)
        (self.root / "quality/release-gates").mkdir(parents=True)
        (self.root / "test-lab/scripts").mkdir(parents=True)
        shutil.copy2(SCRIPT, self.root / "scripts/ci" / SCRIPT.name)
        self.checker = self.root / "scripts/ci/check_dns_ipv6_killswitch_gates.py"
        self.checker.write_text(
            """#!/usr/bin/env python3
import json, os, pathlib, sys
pathlib.Path(os.environ['CHECKER_ARGS']).write_text(json.dumps(sys.argv[1:]))
raise SystemExit(int(os.environ.get('CHECKER_EXIT', '0')))
""",
            encoding="utf-8",
        )
        (self.root / "quality/release-gates/fixture.json").write_text(
            "{}\n", encoding="utf-8"
        )
        (
            self.root / "test-lab/scripts/run-dual-vantage-network-evidence.sh"
        ).write_text("#!/usr/bin/env bash\n", encoding="utf-8")
        self.run_command("git", "init", "-q")
        self.run_command("git", "config", "user.email", "test@example.invalid")
        self.run_command("git", "config", "user.name", "Test")
        self.run_command("git", "add", ".")
        self.run_command("git", "commit", "-qm", "fixture")
        self.artifacts = Path(self.temporary.name) / "artifacts"
        self.artifacts.mkdir()
        self.results = self.artifacts / "results.json"
        self.evidence_dir = self.artifacts / "evidence"
        self.evidence_dir.mkdir()
        self.evidence = self.evidence_dir / "manifest.json"
        self.args_log = self.artifacts / "checker-args.json"
        self.results.write_text("{}\n", encoding="utf-8")
        self.evidence.write_text("{}\n", encoding="utf-8")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_command(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            args, cwd=self.root, check=True, text=True, capture_output=True
        )

    def invoke(self, *, checker_exit: int = 0) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment["CHECKER_ARGS"] = str(self.args_log)
        environment["CHECKER_EXIT"] = str(checker_exit)
        return subprocess.run(
            [
                sys.executable,
                str(self.root / "scripts/ci" / SCRIPT.name),
                "--results",
                str(self.results),
                "--evidence-manifest",
                str(self.evidence),
                "--execution-id",
                "pixel-local-20260722",
                "--execution-attempt",
                "2",
            ],
            cwd=self.root,
            env=environment,
            text=True,
            capture_output=True,
        )

    def test_passes_exact_local_provenance_to_head_checker_snapshot(self) -> None:
        completed = self.invoke()
        self.assertEqual(completed.returncode, 0, completed.stderr)
        checker_args = json.loads(self.args_log.read_text(encoding="utf-8"))
        self.assertIn("--expected-execution-kind", checker_args)
        self.assertEqual(
            checker_args[checker_args.index("--expected-execution-kind") + 1], "local"
        )
        self.assertNotIn("--expected-evidence-run-id", checker_args)
        self.assertNotIn("github-actions", checker_args)
        source_sha = self.run_command("git", "rev-parse", "HEAD").stdout.strip()
        self.assertEqual(
            checker_args[checker_args.index("--expected-source-sha") + 1], source_sha
        )
        self.assertIn(source_sha, completed.stdout)

    def test_dirty_checkout_fails_before_checker(self) -> None:
        (self.root / "dirty.txt").write_text("dirty\n", encoding="utf-8")
        completed = self.invoke()
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("checkout is dirty", completed.stderr)
        self.assertFalse(self.args_log.exists())

    def test_modified_entrypoint_hidden_by_assume_unchanged_is_rejected(self) -> None:
        entrypoint = self.root / "scripts/ci" / SCRIPT.name
        self.run_command("git", "update-index", "--assume-unchanged", str(entrypoint))
        entrypoint.write_text(
            entrypoint.read_text() + "\n# modified\n", encoding="utf-8"
        )
        completed = self.invoke()
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("does not match exact HEAD", completed.stderr)
        self.assertFalse(self.args_log.exists())

    def test_checker_failure_is_propagated(self) -> None:
        completed = self.invoke(checker_exit=7)
        self.assertEqual(completed.returncode, 7)

    def test_symlinked_evidence_is_rejected_before_checker(self) -> None:
        target = self.artifacts / "real-manifest.json"
        target.write_text("{}\n", encoding="utf-8")
        self.evidence.unlink()
        self.evidence.symlink_to(target)
        completed = self.invoke()
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("non-symlink", completed.stderr)
        self.assertFalse(self.args_log.exists())

    def test_invalid_local_execution_identity_is_rejected(self) -> None:
        completed = subprocess.run(
            [
                sys.executable,
                str(self.root / "scripts/ci" / SCRIPT.name),
                "--results",
                str(self.results),
                "--evidence-manifest",
                str(self.evidence),
                "--execution-id",
                "../not-local",
                "--execution-attempt",
                "1",
            ],
            cwd=self.root,
            text=True,
            capture_output=True,
        )
        self.assertEqual(completed.returncode, 2)

    def test_real_checker_rejects_incomplete_bundle_without_acceptance(self) -> None:
        for name in (
            "android_ordinary_physical_attestation.py",
            "android_ordinary_raw_evidence.py",
            "android_ordinary_semantic_oracles.py",
            "check_dns_ipv6_killswitch_gates.py",
            "network_evidence_manifest.py",
            "produce_android_ordinary_gate_results.py",
            "produce_android_ordinary_physical_evidence.py",
            "release_gate_results.py",
        ):
            shutil.copy2(ROOT / "scripts/ci" / name, self.root / "scripts/ci" / name)
        shutil.copytree(
            ROOT / "quality/release-gates",
            self.root / "quality/release-gates",
            dirs_exist_ok=True,
        )
        shutil.copy2(
            ROOT / "test-lab/scripts/run-dual-vantage-network-evidence.sh",
            self.root / "test-lab/scripts/run-dual-vantage-network-evidence.sh",
        )
        self.run_command("git", "add", ".")
        self.run_command("git", "commit", "-qm", "use real checker")
        source_sha = self.run_command("git", "rev-parse", "HEAD").stdout.strip()
        policy = json.loads(
            (
                self.root / "quality/release-gates/dns-ipv6-killswitch-gates.json"
            ).read_text()
        )
        ordinary = {}
        for gate in policy["gates"]:
            source = gate.get("evidenceSources", {}).get(
                "android-client-release", gate.get("evidenceSource")
            )
            if source != "dual-vantage-network-manifest":
                ordinary[gate["id"]] = {
                    "state": "FAIL",
                    "reason": "SOURCE_OWNED_VERIFIER_UNAVAILABLE",
                }
        self.results.write_text(
            json.dumps(
                {
                    "version": "dns_ipv6_killswitch_results_v1",
                    "sourceSha": source_sha,
                    "appliesTo": "android-client-release",
                    "gateResults": ordinary,
                }
            ),
            encoding="utf-8",
        )
        completed = self.invoke()
        self.assertNotEqual(completed.returncode, 0)
        self.assertNotIn("accepted for", completed.stdout)
        self.assertIn("manifest", (completed.stdout + completed.stderr).lower())


if __name__ == "__main__":
    unittest.main()
