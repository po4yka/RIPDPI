from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import struct
import tempfile
import threading
import time
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "test-lab/scripts/network-evidence-ssh-capture.py"


def load_module():
    spec = importlib.util.spec_from_file_location(
        "network_evidence_ssh_capture", MODULE_PATH
    )
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class NetworkEvidenceSshCaptureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.module = load_module()

    def test_remote_command_uses_fixed_tcpdump_and_private_pid_file(self) -> None:
        capture_filter = self.module.build_capture_filter(
            self.module.parse_capture_address("198.51.100.20"),
            [self.module.parse_endpoint("203.0.113.10:443")],
        )
        command = self.module.build_remote_command(
            "wlan0", "a" * 64, 600, capture_filter
        )

        self.assertIn("/usr/bin/tcpdump", command)
        self.assertIn('-i "$interface"', command)
        self.assertIn("/run/ripdpi-network-evidence", command)
        self.assertIn("kill -INT", command)
        self.assertNotIn("wlan0;", command)
        self.assertIn("203.0.113.10", command)
        self.assertNotIn("(ip or ip6)", command)
        session_record = command.index('start_time" "$session_exe" > "$pid_file"')
        child_spawn = command.index("/usr/bin/timeout --foreground --signal=INT")
        self.assertLess(session_record, child_spawn)
        self.assertIn("sudo -n /usr/bin/setsid /bin/sh", command)
        child_identity = command.index('[ "$child_pgid" = "$pid" ]')
        child_signal = command.index('/bin/kill -INT "$child_pid"')
        self.assertLess(child_identity, child_signal)

    def test_capture_filter_requires_narrow_endpoint_pairs(self) -> None:
        peer = self.module.parse_capture_address("198.51.100.20")
        endpoints = [self.module.parse_endpoint("203.0.113.10:443")]
        capture_filter = self.module.build_capture_filter(peer, endpoints)

        self.assertEqual(
            capture_filter,
            "(((src host 198.51.100.20 and dst host 203.0.113.10 and "
            "(tcp dst port 443 or udp dst port 443)) or (src host 203.0.113.10 "
            "and dst host 198.51.100.20 and (tcp src port 443 or udp src port 443))))",
        )
        for unsafe in (
            "any",
            "0.0.0.0:53",
            "127.0.0.1:53",
            "[ff02::1]:53",
            "[2001:db8::1%foo or tcp]:443",
        ):
            with self.subTest(unsafe=unsafe):
                with self.assertRaises(ValueError):
                    self.module.parse_endpoint(unsafe)
        with self.assertRaises(ValueError):
            self.module.build_capture_filter(peer, [])
        with self.assertRaises(ValueError):
            self.module.build_capture_filter(
                peer, [self.module.parse_endpoint("[2001:db8::10]:443")]
            )

    def test_rejects_unsafe_ssh_and_interface_values(self) -> None:
        for value in ("raspi;id", "-oProxyCommand=id", "host name", ""):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    self.module.validate_ssh_name(value, "target")
        for value in ("eth0;id", "../../dev/null", "all", ""):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    self.module.validate_interface(value)

    def test_metadata_is_redacted_and_binds_raw_capture(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            capture = root / "capture.pcap"
            metadata = root / "capture.json"
            capture.write_bytes(
                struct.pack("<IHHIIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 1)
            )

            self.module.write_metadata(
                output=metadata,
                capture=capture,
                role="client-underlay",
                correlation_id="b" * 64,
                started_at=100,
                finished_at=101,
                packet_count=0,
            )

            document = json.loads(metadata.read_text(encoding="utf-8"))
            self.assertEqual(
                document["rawCaptureSha256"],
                hashlib.sha256(capture.read_bytes()).hexdigest(),
            )
            self.assertEqual(document["packetCount"], 0)
            published = metadata.read_bytes()
            self.assertNotIn(b"raspi", published)
            self.assertNotIn(b"wlan0", published)

    def test_classic_pcap_packet_count_rejects_truncation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            capture = Path(directory) / "capture.pcap"
            header = struct.pack("<IHHIIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 1)
            packet = b"payload"
            capture.write_bytes(
                header + struct.pack("<IIII", 1, 0, len(packet), len(packet)) + packet
            )
            self.assertEqual(self.module.count_classic_pcap_packets(capture), 1)

            capture.write_bytes(capture.read_bytes()[:-1])
            with self.assertRaises(ValueError):
                self.module.count_classic_pcap_packets(capture)

    def test_output_files_must_not_be_symlinks_or_alias_each_other(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            capture = root / "capture.pcap"
            metadata = root / "capture.json"
            capture.touch()
            os.symlink(capture, metadata)
            with self.assertRaises(ValueError):
                self.module.prepare_paths(
                    capture, metadata, root / "ready", root / "stop"
                )

            metadata.unlink()
            capture.unlink()
            (root / "stop").touch()
            with self.assertRaises(ValueError):
                self.module.prepare_paths(
                    capture, metadata, root / "ready", root / "stop"
                )
            (root / "stop").unlink()
            with self.assertRaises(ValueError):
                self.module.prepare_paths(
                    capture, metadata, root / "same", root / "same"
                )

    def test_capture_size_limit_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            capture = Path(directory) / "capture.pcap"
            with capture.open("wb") as handle:
                handle.truncate(self.module.MAX_CAPTURE_BYTES + 1)
            with self.assertRaises(ValueError):
                self.module.count_classic_pcap_packets(capture)

    def test_failure_cleanup_removes_private_capture_and_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            capture = root / "capture.pcap"
            metadata = root / "capture.json"
            ready = root / "ready"
            for path in (capture, metadata, ready):
                path.write_bytes(b"private packet payload")

            self.module.remove_failed_outputs(capture, metadata, ready)

            self.assertFalse(capture.exists())
            self.assertFalse(metadata.exists())
            self.assertFalse(ready.exists())

    def test_fake_ssh_capture_verifies_cleanup_and_required_marker(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake_ssh = root / "ssh"
            calls = root / "calls"
            marker = "a" * 64
            fake_ssh.write_text(
                """#!/usr/bin/env python3
import os
import struct
import sys

with open(os.environ["RIPDPI_TEST_SSH_CALLS"], "a", encoding="ascii") as log:
    if "ripdpi-capture" not in sys.argv[-1]:
        command = sys.argv[-1]
        required = ("/proc/$pid/stat", "expected_start", "expected_exe", '"-$pid"')
        if not all(value in command for value in required):
            log.write("cleanup:unsafe-contract\\n")
            raise SystemExit(90)
        state_path = os.environ.get("RIPDPI_TEST_REMOTE_STATE")
        if state_path and os.path.exists(state_path):
            state = open(state_path, encoding="ascii").read()
            if state != "session-published:child-live":
                log.write("cleanup:launch-window:invalid-state\\n")
                raise SystemExit(91)
            os.unlink(state_path)
            log.write("cleanup:launch-window:group-absent\\n")
            raise SystemExit(0)
        mode = os.environ.get("RIPDPI_TEST_CLEANUP_MODE", "live")
        if mode == "live":
            log.write("cleanup:identity-ok:int:absent\\n")
            raise SystemExit(0)
        if mode == "stubborn":
            log.write("cleanup:identity-ok:int:kill:absent\\n")
            raise SystemExit(0)
        if mode == "stale":
            log.write("cleanup:identity-mismatch\\n")
            raise SystemExit(74)
        log.write("cleanup:identity-ok:int:kill:still-live\\n")
        raise SystemExit(75)
    log.write("capture\\n")
if os.environ.get("RIPDPI_TEST_FAIL_BEFORE_READY") == "1":
    open(os.environ["RIPDPI_TEST_REMOTE_STATE"], "w", encoding="ascii").write(
        "session-published:child-live"
    )
    raise SystemExit(23)
marker = ("RIPDPI-EVIDENCE-V2:" + os.environ["RIPDPI_TEST_MARKER"]).encode("ascii")
packet = b"\\x00" * 42 + marker
payload = struct.pack("<IHHIIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 1)
payload += struct.pack("<IIII", 1, 0, len(packet), len(packet)) + packet
sys.stdout.buffer.write(payload)
sys.stdout.buffer.flush()
sys.stderr.write("RIPDPI_CAPTURE_READY\\n")
sys.stderr.flush()
sys.stdin.buffer.readline()
""",
                encoding="utf-8",
            )
            fake_ssh.chmod(0o700)
            previous_ssh = self.module.SSH
            previous_calls = os.environ.get("RIPDPI_TEST_SSH_CALLS")
            previous_marker = os.environ.get("RIPDPI_TEST_MARKER")
            remote_state = root / "remote-state"
            self.module.SSH = fake_ssh
            os.environ["RIPDPI_TEST_SSH_CALLS"] = str(calls)
            os.environ["RIPDPI_TEST_MARKER"] = marker
            try:
                ready = root / "ready"
                stop = root / "stop"
                args = argparse.Namespace(
                    role="external-observer",
                    ssh_target="observer",
                    jump=None,
                    interface="eth0",
                    peer_address="198.51.100.20",
                    endpoint=["203.0.113.10:443"],
                    correlation_id="b" * 64,
                    ready_file=str(ready),
                    stop_file=str(stop),
                    output_pcap=str(root / "capture.pcap"),
                    metadata_output=str(root / "capture.json"),
                    required_marker_sha256=marker,
                    max_seconds=5,
                )

                def stop_after_ready() -> None:
                    deadline = time.monotonic() + 3
                    while not ready.exists() and time.monotonic() < deadline:
                        time.sleep(0.01)
                    stop.touch()

                stopper = threading.Thread(target=stop_after_ready)
                stopper.start()
                self.module.capture(args)
                stopper.join()
                self.assertTrue((root / "capture.pcap").exists())
                self.assertTrue((root / "capture.json").exists())
                self.assertEqual(
                    calls.read_text(encoding="ascii").splitlines(),
                    ["capture", "cleanup:identity-ok:int:absent"],
                )

                for path in (root / "capture.pcap", root / "capture.json", ready, stop):
                    path.unlink()
                calls.write_text("", encoding="ascii")
                args.required_marker_sha256 = "c" * 64
                stopper = threading.Thread(target=stop_after_ready)
                stopper.start()
                with self.assertRaises(RuntimeError):
                    self.module.capture(args)
                stopper.join()
                self.assertFalse((root / "capture.pcap").exists())
                self.assertFalse((root / "capture.json").exists())
                self.assertFalse(ready.exists())
                self.assertEqual(
                    calls.read_text(encoding="ascii").splitlines(),
                    ["capture", "cleanup:identity-ok:int:absent"],
                )

                stop.unlink()
                calls.write_text("", encoding="ascii")
                os.environ["RIPDPI_TEST_FAIL_BEFORE_READY"] = "1"
                os.environ["RIPDPI_TEST_REMOTE_STATE"] = str(remote_state)
                with self.assertRaises(RuntimeError):
                    self.module.capture(args)
                self.assertFalse((root / "capture.pcap").exists())
                self.assertFalse((root / "capture.json").exists())
                self.assertFalse(ready.exists())
                self.assertEqual(
                    calls.read_text(encoding="ascii").splitlines(),
                    ["capture", "cleanup:launch-window:group-absent"],
                )
                self.assertFalse(remote_state.exists())

                calls.write_text("", encoding="ascii")
                os.environ["RIPDPI_TEST_CLEANUP_MODE"] = "stubborn"
                self.module._cleanup_remote("observer", None, "d" * 64)
                self.assertEqual(
                    calls.read_text(encoding="ascii").splitlines(),
                    ["cleanup:identity-ok:int:kill:absent"],
                )

                for mode, expected in (
                    ("stale", "cleanup:identity-mismatch"),
                    ("survives", "cleanup:identity-ok:int:kill:still-live"),
                ):
                    calls.write_text("", encoding="ascii")
                    os.environ["RIPDPI_TEST_CLEANUP_MODE"] = mode
                    with self.assertRaises(RuntimeError):
                        self.module._cleanup_remote("observer", None, "d" * 64)
                    self.assertEqual(
                        calls.read_text(encoding="ascii").splitlines(),
                        [expected, expected, expected],
                    )
            finally:
                self.module.SSH = previous_ssh
                if previous_calls is None:
                    os.environ.pop("RIPDPI_TEST_SSH_CALLS", None)
                else:
                    os.environ["RIPDPI_TEST_SSH_CALLS"] = previous_calls
                if previous_marker is None:
                    os.environ.pop("RIPDPI_TEST_MARKER", None)
                else:
                    os.environ["RIPDPI_TEST_MARKER"] = previous_marker
                os.environ.pop("RIPDPI_TEST_FAIL_BEFORE_READY", None)
                os.environ.pop("RIPDPI_TEST_CLEANUP_MODE", None)
                os.environ.pop("RIPDPI_TEST_REMOTE_STATE", None)


if __name__ == "__main__":
    unittest.main()
