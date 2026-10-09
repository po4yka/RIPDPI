"""Runner failure controls use isolated temporary Git repositories."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from execution import run_suite, source_identity, signal_group
from verify_report import verify
from contract import ContractError
from test_contract import manifest


class ExecutionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.repo = self.base / "repo"
        self.repo.mkdir()
        subprocess.run(["git", "init", "-q", str(self.repo)], check=True)
        subprocess.run(
            [
                "git",
                "-C",
                str(self.repo),
                "-c",
                "user.name=Lab Test",
                "-c",
                "user.email=test@example.invalid",
                "commit",
                "--allow-empty",
                "--no-gpg-sign",
                "-qm",
                "test fixture",
            ],
            check=True,
        )
        adapter = self.repo / "scripts/ci/run-android-local-acceptance.py"
        adapter.parent.mkdir(parents=True)
        adapter.write_text("# test adapter\n")
        self.out = self.base / "out"
        self.catalog = manifest()

    def executor(self, command, repo, log, timeout):
        out = Path(command[command.index("--out-dir") + 1])
        out.mkdir()
        (out / "receipts.json").write_text('{"nonce":"test-only"}')
        (out / "result.json").write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "run_id": "run-test",
                    "scenario_id": "android-xray",
                    "source_sha": source_identity(repo)["source_sha"],
                    "tier": "android-tun",
                    "status": "passed",
                    "checks": [
                        {"id": "payload", "passed": True},
                        {"id": "no-bypass", "passed": True},
                    ],
                    "cleanup": {"passed": True},
                    "artifacts": ["receipts.json"],
                }
            )
        )
        return 0, False

    def run_suite(self, executor):
        return run_suite(
            self.repo,
            self.catalog,
            self.catalog["scenarios"],
            "run-test",
            self.out,
            {},
            executor,
        )

    def test_permission_error_is_ignored_only_for_absent_group(self):
        with (
            patch("execution.os.killpg", side_effect=PermissionError),
            patch("execution.subprocess.check_output", return_value="123 456"),
        ):
            signal_group(789, 15)
            with self.assertRaises(PermissionError):
                signal_group(123, 15)

    def test_valid_run(self):
        report = self.run_suite(self.executor)
        self.assertEqual("passed", report["status"])
        self.assertTrue((self.out / "report.xml").is_file())
        self.assertNotIn("test-only", (self.out / "report.json").read_text())

    def test_saved_evidence_revalidation_rejects_changes(self):
        self.run_suite(self.executor)
        verify(self.out)
        (self.out / "android-xray/receipts.json").write_text("changed")
        with self.assertRaises(ContractError):
            verify(self.out)

    def test_saved_evidence_revalidation_rejects_lost_row(self):
        self.run_suite(self.executor)
        path = self.out / "report.json"
        report = json.loads(path.read_text())
        report["results"] = []
        path.write_text(json.dumps(report))
        with self.assertRaises(ContractError):
            verify(self.out)

    def test_zero_exit_without_evidence_fails(self):
        report = self.run_suite(lambda *args: (0, False))
        self.assertEqual("failed", report["status"])

    def test_nonzero_exit_cannot_pass(self):
        def fail(*args):
            self.executor(*args)
            return 1, False

        self.assertEqual("failed", self.run_suite(fail)["status"])

    def test_timeout_cannot_pass(self):
        self.assertEqual("failed", self.run_suite(lambda *args: (124, True))["status"])

    def test_source_edit_invalidates_run(self):
        def edit(*args):
            self.executor(*args)
            (self.repo / "changed.py").write_text("different source")
            return 0, False

        report = self.run_suite(edit)
        self.assertEqual("failed", report["status"])
        self.assertFalse(report["source_unchanged"])
        self.assertIn("source-integrity", (self.out / "report.xml").read_text())

    def test_existing_output_rejected(self):
        self.out.mkdir()
        (self.out / "result.json").write_text("user-owned")
        with self.assertRaises(FileExistsError):
            self.run_suite(self.executor)
        self.assertEqual("user-owned", (self.out / "result.json").read_text())

    def test_interrupt_is_failure(self):
        def interrupt(*args):
            raise KeyboardInterrupt()

        report = self.run_suite(interrupt)
        self.assertEqual("failed", report["status"])
        self.assertTrue(report["interrupted"])

    def test_remote_timeout_requests_explicit_cleanup(self):
        adapter = self.repo / "test-lab/acceptance/vm/run.py"
        adapter.parent.mkdir(parents=True)
        adapter.write_text("# adapter")
        scenario = self.catalog["scenarios"][0]
        scenario.update(id="routed-delay", adapter="vm", tier="routed-fault")
        with (
            patch("vm_transport.vm_command", return_value=["remote"]),
            patch("vm_transport.cancel_vm", return_value=True) as cancel,
        ):
            report = run_suite(
                self.repo,
                self.catalog,
                [scenario],
                "run-test",
                self.out,
                {"vm": "ripdpi-acceptance-test"},
                lambda *args: (124, True),
            )
        cancel.assert_called_once()
        self.assertEqual("failed", report["status"])
        self.assertTrue(report["results"][0]["remote_cleanup"])

    def test_platform_mismatch_is_blocked_not_skipped(self):
        self.catalog["scenarios"][0]["platforms"] = ["linux"]
        with patch("execution.platform.system", return_value="Darwin"):
            report = self.run_suite(self.executor)
        self.assertEqual("blocked", report["results"][0]["status"])
        self.assertEqual("failed", report["status"])
        self.assertIn('skipped="0"', (self.out / "report.xml").read_text())


if __name__ == "__main__":
    unittest.main()
