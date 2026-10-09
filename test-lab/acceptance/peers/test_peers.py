"""Fail-closed driver contracts. These checks are not protocol acceptance evidence."""

import importlib.util
import json
import re
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(DIRECTORY))
catalog = importlib.import_module("catalog")
hysteria = importlib.import_module("hysteria")

SPEC = importlib.util.spec_from_file_location(
    "acceptance_peer_runner", DIRECTORY / "run.py"
)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class ExactExecutionTest(unittest.TestCase):
    def test_exact_result_is_required(self):
        correct = "test selected ... ok\n\ntest result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 8 filtered out; finished in 0.10s\n"
        self.assertTrue(runner.exact_success(correct, "selected"))
        for output in (
            correct.replace("selected", "different"),
            correct.replace("1 passed", "0 passed"),
            correct.replace("0 ignored", "1 ignored"),
            correct.replace("... ok", "... ignored"),
            correct + correct,
            "test selected ... ok\n",
        ):
            with self.subTest(output=output):
                self.assertFalse(runner.exact_success(output, "selected"))

    def test_exact_command_never_uses_substring_as_acceptance(self):
        case = catalog.SCENARIOS["native-chain-tcp"]
        command = runner.cargo_command(case)
        self.assertIn("--locked", command)
        self.assertIn("--exact", command)
        self.assertIn("--ignored", command)
        self.assertNotIn("--ignored", runner.cargo_command(case, True))

    def test_catalog_tests_exist_in_source(self):
        for identifier, case in catalog.SCENARIOS.items():
            if case["driver"] == "cargo":
                root = runner.RUST / "crates" / case["package"]
                if case["target"] == "lib":
                    self.assertTrue((root / "src/lib.rs").is_file(), identifier)
                elif case["target"] == "bin":
                    self.assertTrue((root / "src/main.rs").is_file(), identifier)
                function = "fn " + case["test"].split("::")[-1] + "("
                self.assertTrue(
                    any(function in path.read_text() for path in root.rglob("*.rs")),
                    identifier,
                )

    def test_relay_registry_has_an_explicit_evidence_tier(self):
        source = (
            runner.RUST / "crates/ripdpi-relay-core/src/config/backend.rs"
        ).read_text()
        registered = set(re.findall(r'Self::\w+\(_\) => "([a-z0-9_]+)"', source))
        self.assertTrue(registered)
        catalogued = {case["protocol"] for case in catalog.SCENARIOS.values()}
        self.assertFalse(registered - catalogued, registered - catalogued)

    def test_all_independent_cases_require_identity(self):
        for case in catalog.SCENARIOS.values():
            if case["tier"] == "independent-peer":
                self.assertIn("pinned-peer-identity", case["checks"])


class PeerLifecycleTest(unittest.TestCase):
    def test_interruption_reaps_owned_session(self):
        ledger = runner.ProcessLedger()
        with self.assertRaises(InterruptedError):
            with ledger:
                child = subprocess.Popen(
                    [sys.executable, "-c", "import time; time.sleep(60)"],
                    start_new_session=True,
                )
                raise InterruptedError("test cancellation")
        self.assertTrue(ledger.verified)
        self.assertIsNotNone(child.poll())
        self.assertFalse(ledger.group_exists(child.pid))

    def test_destinations_record_tcp_and_udp_then_close(self):
        data = b"unique-run-payload"
        sink = hysteria.Destinations(data)
        tcp_address = sink.tcp.getsockname()
        try:
            with socket.create_connection(tcp_address, timeout=2) as connection:
                connection.sendall(data[:5])
                connection.sendall(data[5:])
                echoed = b""
                while len(echoed) < len(data):
                    echoed += connection.recv(1024)
                self.assertEqual(data, echoed)
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as connection:
                connection.settimeout(2)
                connection.sendto(data, sink.udp.getsockname())
                self.assertEqual(data, connection.recv(1024))
        finally:
            sink.close()
        self.assertEqual(
            {"tcp", "udp"}, {receipt["transport"] for receipt in sink.receipts}
        )
        self.assertFalse(sink.errors)
        with self.assertRaises(OSError):
            socket.create_connection(tcp_address, timeout=0.2)

    def test_missing_binary_never_downloads_at_runtime(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(hysteria, "asset", return_value="linux-arm64"),
            patch.object(hysteria.urllib.request, "urlopen") as network,
        ):
            with self.assertRaises(FileNotFoundError):
                hysteria.binary(Path(directory))
            network.assert_not_called()

    def test_cached_binary_digest_is_verified(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(hysteria, "asset", return_value="linux-arm64"),
        ):
            (Path(directory) / "hysteria-v2.9.0-linux-arm64").write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                hysteria.binary(Path(directory))

    def test_missing_prerequisite_produces_blocked_bound_report(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(
                runner, "execute", side_effect=FileNotFoundError("missing peer")
            ),
        ):
            out = Path(directory) / "evidence"
            status = runner.main(
                [
                    "--scenario",
                    "independent-hysteria2-tcp-udp",
                    "--run-id",
                    "test-run",
                    "--out-dir",
                    str(out),
                ]
            )
            self.assertEqual(2, status)
            result = json.loads((out / "result.json").read_text())
            self.assertEqual("blocked", result["status"])
            self.assertEqual("test-run", result["run_id"])
            self.assertEqual("independent-hysteria2-tcp-udp", result["scenario_id"])
            self.assertTrue(result["cleanup"]["passed"])
            self.assertTrue(all(not check["passed"] for check in result["checks"]))
            self.assertEqual(0o700, out.stat().st_mode & 0o777)

    def test_stale_output_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "result.json").write_text("previous run")
            with self.assertRaises(SystemExit):
                runner.main(
                    [
                        "--scenario",
                        "native-tuic-tcp",
                        "--run-id",
                        "new-run",
                        "--out-dir",
                        directory,
                    ]
                )
            self.assertEqual(
                "previous run", (Path(directory) / "result.json").read_text()
            )

    def test_interrupted_driver_reports_failure(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(runner, "execute", side_effect=InterruptedError("terminated")),
        ):
            runner.main(
                [
                    "--scenario",
                    "native-tuic-tcp",
                    "--run-id",
                    "interrupted",
                    "--out-dir",
                    directory,
                ]
            )
            result = json.loads((Path(directory) / "result.json").read_text())
            self.assertEqual("failed", result["status"])
            self.assertFalse(result["checks"][0]["passed"])


if __name__ == "__main__":
    unittest.main()
