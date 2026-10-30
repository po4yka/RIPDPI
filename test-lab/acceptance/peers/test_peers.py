"""Fail-closed driver contracts. These checks are not protocol acceptance evidence."""

from contextlib import nullcontext
import importlib.util
import json
import os
import re
from pathlib import Path
import socket
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

DIRECTORY = Path(__file__).resolve().parent
sys.path.insert(0, str(DIRECTORY))
catalog = importlib.import_module("catalog")
hysteria = importlib.import_module("hysteria")
hysteria_hopping = importlib.import_module("hysteria_hopping")
naiveproxy = importlib.import_module("naiveproxy")

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


class AwgExecutionTest(unittest.TestCase):
    TEST = "standalone_profile_exchanges_tcp_and_udp_with_independent_awg_peer"
    IDENTITY = "Independent peer: amneziawg-go v3.1.20260828 (b5928efb6ca19f0153958460c3d141f04abc5c2e); sha256=" + "a" * 64 + "\n"

    def session(self, mtu, cycle):
        return (f"MTU {mtu}, session {cycle}\n"
                f"test {self.TEST} ... ok\n"
                "test result: ok. 1 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.10s\n")

    def matrix(self):
        return self.IDENTITY + "".join(self.session(mtu, cycle)
                                      for mtu in (1280, 1420) for cycle in range(4))

    def run_adapter(self, output):
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "result"
            with patch.object(runner, "execute", side_effect=lambda *_: print(output, end="")):
                code = runner.main(["--scenario", "independent-awg-tcp-udp",
                                    "--run-id", "awg-regression", "--out-dir", str(out)])
            return code, json.loads((out / "result.json").read_text())

    def test_all_eight_exact_sessions_pass(self):
        code, result = self.run_adapter(self.matrix())
        self.assertEqual(code, 0)
        self.assertEqual(result["status"], "passed")
        self.assertEqual(result["peer_identity"]["version"], "v3.1.20260828")
        self.assertEqual(result["peer_identity"]["revision"], "b5928efb6ca19f0153958460c3d141f04abc5c2e")

    def test_partial_duplicate_failed_or_unpinned_matrix_cannot_pass(self):
        output = self.matrix()
        invalid = {
            "one-session": self.IDENTITY + self.session(1280, 0),
            "missing-session": output.removesuffix(self.session(1420, 3)),
            "duplicate-session": output.replace("MTU 1420, session 3", "MTU 1420, session 2"),
            "wrong-mtu": output.replace("MTU 1420, session 3", "MTU 1421, session 3"),
            "failed-session": output.replace("... ok", "... FAILED", 1),
            "ignored-session": output.replace("... ok", "... ignored", 1),
            "wrong-test": output.replace(self.TEST, "different", 1),
            "extra-result": output + self.session(1420, 3),
            "unscoped-result": self.session(1280, 0).split("\n", 1)[1] + output,
            "missing-pin": output.removeprefix(self.IDENTITY),
            "wrong-pin": output.replace("v3.1.20260828", "v0.2.18"),
            "wrong-revision": output.replace("b5928efb6ca19f0153958460c3d141f04abc5c2e", "b" * 40),
            "duplicate-pin": self.IDENTITY + output,
        }
        for reason, transcript in invalid.items():
            with self.subTest(reason=reason):
                code, result = self.run_adapter(transcript)
                self.assertEqual(code, 2)
                self.assertEqual(result["status"], "failed")


class HoppingFailureEvidenceTest(unittest.TestCase):
    def test_shared_hysteria_cases_use_the_verified_binary_pin(self):
        for case in catalog.SCENARIOS.values():
            if case["driver"] in ("hysteria2", "hysteria2-hopping"):
                self.assertEqual(case["peer_identity"]["version"], hysteria.VERSION)
        self.assertEqual(set(hysteria.DIGESTS), {"darwin-arm64", "linux-amd64", "linux-arm64"})
        self.assertTrue(all(re.fullmatch(r"[a-f0-9]{64}", value) for value in hysteria.DIGESTS.values()))

    def test_fragmented_udp_oracle_requires_every_distinct_packet(self):
        payload = b"x" * 8208
        expected = hysteria_hopping.expected_udp_hashes(payload)
        self.assertEqual(len(expected), 60)
        changed = hysteria_hopping.expected_udp_hashes(b"y" * 8208)
        self.assertFalse(expected & changed)

    def test_failed_rust_execution_keeps_receipts_and_failure(self):
        case = catalog.SCENARIOS["independent-hysteria2-hopping-ipv4-plain-fixed"]
        with tempfile.TemporaryDirectory() as directory:
            args = unittest.mock.Mock(cache_dir=Path(directory), out_dir=Path(directory), run_id="failure-probe")
            receipt = {"transport": "udp", "bytes": 2736, "sha256": "1" * 64}
            peer = {
                "env": {}, "receipts": [receipt],
                "redirects": unittest.mock.Mock(receipts=[{"remote_port": 443, "local_port": 1234, "time": 1.0}]),
                "wire_source": {"kind": "kotlin-serializer", "sha256": "2" * 64},
            }
            result = {"artifacts": [], "checks": []}
            failure = subprocess.CalledProcessError(101, ["cargo", "test"])
            with patch.object(runner, "checked", side_effect=[None, failure]), \
                 patch.object(hysteria_hopping, "peer", return_value=nullcontext(peer)):
                with self.assertRaises(subprocess.CalledProcessError) as caught:
                    runner.execute(case, args, result)
            self.assertIs(caught.exception, failure)
            saved = json.loads((Path(directory) / "receipts.json").read_text())
            self.assertEqual(saved["receipts"], [receipt])
            self.assertEqual(saved["wire_source"], peer["wire_source"])
            self.assertEqual(result["artifacts"], ["receipts.json"])
            self.assertEqual(result["checks"], [])


class PeerLifecycleTest(unittest.TestCase):
    def test_darwin_permission_only_means_gone_when_inventory_agrees(self):
        with patch.object(hysteria.os, 'killpg', side_effect=PermissionError), \
             patch.object(sys, 'platform', 'darwin'), \
             patch.object(hysteria.subprocess, 'check_output', return_value='12\n34\n'):
            self.assertFalse(runner.ProcessLedger.group_exists(56))
            self.assertTrue(runner.ProcessLedger.group_exists(34))

    def test_other_platform_permission_errors_are_not_absence(self):
        with patch.object(hysteria.os, 'killpg', side_effect=PermissionError), \
             patch.object(sys, 'platform', 'linux'), \
             patch.object(hysteria.subprocess, 'check_output') as inventory:
            with self.assertRaises(PermissionError):
                runner.ProcessLedger.group_exists(56)
            inventory.assert_not_called()

    def test_failed_darwin_inventory_cannot_prove_absence(self):
        with patch.object(hysteria.os, 'killpg', side_effect=PermissionError), \
             patch.object(sys, 'platform', 'darwin'), \
             patch.object(hysteria.subprocess, 'check_output', side_effect=subprocess.CalledProcessError(1, 'ps')):
            with self.assertRaises(subprocess.CalledProcessError):
                runner.ProcessLedger.group_exists(56)

    def test_permission_denied_signal_preserves_a_live_group(self):
        process = unittest.mock.Mock(pid=34)
        with patch.object(hysteria.os, 'killpg', side_effect=PermissionError), \
             patch.object(sys, 'platform', 'darwin'), \
             patch.object(hysteria.subprocess, 'check_output', return_value='34\n'):
            with self.assertRaises(PermissionError):
                hysteria.stop_group(process)
            process.wait.assert_not_called()

    def test_permission_denied_signal_accepts_a_verified_absent_group(self):
        process = unittest.mock.Mock(pid=56)
        process.poll.return_value = 0
        with patch.object(hysteria.os, 'killpg', side_effect=PermissionError), \
             patch.object(sys, 'platform', 'darwin'), \
             patch.object(hysteria.subprocess, 'check_output', return_value='34\n'):
            hysteria.stop_group(process)
        self.assertEqual(process.wait.call_count, 2)

    def test_preparation_sigterm_reaps_compiler_session(self):
        self.preparation_signal_reaps_compiler_session(signal.SIGTERM)

    def test_preparation_sigint_reaps_compiler_session(self):
        self.preparation_signal_reaps_compiler_session(signal.SIGINT)

    def preparation_signal_reaps_compiler_session(self, signum):
        with tempfile.TemporaryDirectory() as directory:
            ready = Path(directory)/'child.pid'
            script = '''import importlib.util, pathlib, subprocess, sys
sys.path.insert(0, sys.argv[1])
spec = importlib.util.spec_from_file_location('runner', pathlib.Path(sys.argv[1])/'run.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
def preparation(case, args):
    child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], start_new_session=True)
    ready = pathlib.Path(sys.argv[2])
    temporary = ready.with_suffix('.tmp')
    temporary.write_text(str(child.pid))
    temporary.replace(ready)
    child.wait()
runner.prepare = preparation
runner.main(['--prepare', '--scenario', 'native-tuic-tcp'])
'''
            adapter = subprocess.Popen([sys.executable, '-c', script, str(DIRECTORY), str(ready)],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            child_pid = None
            try:
                deadline = time.monotonic()+5
                while not ready.exists():
                    if adapter.poll() is not None or time.monotonic() >= deadline:
                        self.fail('preparation compiler did not start')
                    time.sleep(.02)
                child_pid = int(ready.read_text())
                adapter.send_signal(signum)
                self.assertNotEqual(adapter.wait(timeout=10), 0)
                self.assertFalse(runner.ProcessLedger.group_exists(child_pid))
            finally:
                if adapter.poll() is None:
                    adapter.kill()
                    adapter.wait(timeout=5)
                if child_pid is not None:
                    try:
                        os.killpg(child_pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass

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

    def test_redirects_preserve_each_physical_udp_path(self):
        for family, host in ((socket.AF_INET, "127.0.0.1"), (socket.AF_INET6, "::1")):
            with self.subTest(host=host), socket.socket(family, socket.SOCK_DGRAM) as upstream:
                upstream.bind((host, 0))
                upstream.settimeout(2)
                redirects = hysteria_hopping.Redirects((host, upstream.getsockname()[1]))
                try:
                    for port in redirects.ports:
                        with socket.socket(family, socket.SOCK_DGRAM) as client:
                            client.settimeout(2)
                            client.sendto(b"physical-path", (host, port))
                            data, source = upstream.recvfrom(1024)
                            upstream.sendto(data, source)
                            echoed, remote = client.recvfrom(1024)
                            self.assertEqual(echoed, b"physical-path")
                            self.assertEqual(remote[1], port)
                    self.assertEqual(len(redirects.receipts), 3)
                    self.assertFalse(redirects.errors)
                finally:
                    redirects.close()

    def test_persistent_destination_keeps_one_connection(self):
        sink = hysteria.Destinations(b"roundtrip", persistent=True)
        try:
            with socket.create_connection(sink.tcp.getsockname(), timeout=2) as client:
                for _ in range(3):
                    client.sendall(b"roundtrip")
                    self.assertEqual(client.recv(9), b"roundtrip")
            self.assertEqual(sink.tcp_connections, 1)
            self.assertEqual(len(sink.receipts), 3)
        finally:
            sink.close()

    def test_requested_kotlin_fixture_never_falls_back(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {'RIPDPI_HYSTERIA_WIRE_FIXTURE_DIR': directory}):
            with self.assertRaises(FileNotFoundError):
                hysteria_hopping.fixture_input({'randomized': False})
            path = Path(directory) / 'peer-flat-fixed.json'
            path.write_text(json.dumps({'schemaVersion': 10, 'kind': 'hysteria2', 'hysteriaPortHopPorts': '1-65535', 'hysteriaPortHopIntervalMillis': 5000}))
            with self.assertRaises(ValueError):
                hysteria_hopping.fixture_input({'randomized': False})
            path.write_text(json.dumps({'schemaVersion': 10, 'kind': 'hysteria2', 'hysteriaPortHopPorts': '42071,42073,42079', 'hysteriaPortHopIntervalMillis': 5000}))
            fixture, ports, source = hysteria_hopping.fixture_input({'randomized': False})
            self.assertEqual(fixture, path)
            self.assertEqual(ports, [42071, 42073, 42079])
            self.assertEqual(source['kind'], 'kotlin-serializer')
            self.assertEqual(len(source['sha256']), 64)

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
            (Path(directory) / "hysteria-v2.13.0-linux-arm64").write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                hysteria.binary(Path(directory))

    def test_prepared_naiveproxy_server_identity_and_digest_are_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            with self.assertRaises(FileNotFoundError):
                naiveproxy.binary(cache)
            base = naiveproxy.root(cache)
            base.mkdir(parents=True)
            server = base / "caddy-forwardproxy-naive"
            server.write_bytes(b"prepared server")
            record = base / "peer.json"
            record.write_text(json.dumps(dict(naiveproxy.identity(), sha256=naiveproxy.go_toolchain.digest(server))))
            self.assertEqual(server, naiveproxy.binary(cache)[0])
            server.write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                naiveproxy.binary(cache)
            record.write_text(json.dumps(dict(naiveproxy.identity(), revision="0" * 40, sha256="")))
            with self.assertRaisesRegex(ValueError, "pinned sources"):
                naiveproxy.binary(cache)

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
