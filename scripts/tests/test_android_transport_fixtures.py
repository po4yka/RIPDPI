"""Bounds and fault isolation for owned Android transport fixtures."""

from pathlib import Path
from contextlib import ExitStack
import socket
import io
import json
import subprocess
import tempfile
from unittest.mock import patch
from types import SimpleNamespace
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ci"))
from android_hysteria_fixture import HysteriaFixture, UdpGate, prepare_binary
from android_udp_bridge import MAX_FLOWS
from android_nginx_fixture import NginxFixture, configuration, private_host


class FixtureTests(unittest.TestCase):
    def udp_clients(self, stack, count):
        upstream = stack.enter_context(socket.socket(socket.AF_INET, socket.SOCK_DGRAM))
        upstream.bind(("127.0.0.1", 0))
        upstream.settimeout(1)
        clients = []
        for _ in range(count):
            client = stack.enter_context(
                socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            )
            client.bind(("127.0.0.1", 0))
            client.settimeout(1)
            clients.append(client)
        return upstream, clients

    def udp_roundtrip(self, gate, upstream, client, packet):
        client.sendto(packet, ("127.0.0.1", gate.port))
        received, origin = upstream.recvfrom(64)
        self.assertEqual(packet, received)
        upstream.sendto(received, origin)
        self.assertEqual(packet, client.recv(64))
        return origin

    def test_udp_gate_preserves_full_carrier_flow_budget(self):
        self.assertEqual(64, MAX_FLOWS)
        with ExitStack() as stack:
            upstream, clients = self.udp_clients(stack, MAX_FLOWS)
            gate = UdpGate("127.0.0.1", "127.0.0.1", upstream.getsockname())
            try:
                origins = set()
                for index, client in enumerate(clients, 1):
                    origins.add(
                        self.udp_roundtrip(gate, upstream, client, str(index).encode())
                    )
                    if index in (17, MAX_FLOWS):
                        self.udp_roundtrip(gate, upstream, clients[0], b"earlier-flow")
                self.assertEqual(MAX_FLOWS, len(origins))
                self.assertEqual(MAX_FLOWS, len(gate.connections))
                self.assertIsNone(gate.error)
            finally:
                gate.close()
            self.assertFalse(gate.thread.is_alive())
            self.assertEqual(-1, gate.listener.fileno())
            self.assertTrue(
                all(
                    connection.fileno() == -1
                    for connection in gate.connections.values()
                )
            )

    def test_udp_gate_overflow_stops_forwarding_and_closes_owned_sockets(self):
        with ExitStack() as stack:
            root = Path(stack.enter_context(tempfile.TemporaryDirectory()))
            upstream, clients = self.udp_clients(stack, MAX_FLOWS + 1)
            gate = UdpGate("127.0.0.1", "127.0.0.1", upstream.getsockname())
            fixture = HysteriaFixture(
                root, root / "tls", root / "binary", "127.0.0.1", "127.0.0.1", {}, 1
            )
            fixture.gate = gate
            try:
                for index, client in enumerate(clients[:-1]):
                    self.udp_roundtrip(gate, upstream, client, str(index).encode())
                clients[-1].sendto(b"overflow", ("127.0.0.1", gate.port))
                gate.thread.join(timeout=3)
                self.assertFalse(gate.thread.is_alive())
                self.assertEqual(MAX_FLOWS, len(gate.connections))
                self.assertEqual(
                    "owned UDP client socket bound exceeded", str(gate.error)
                )
                for client in (clients[0], clients[-1]):
                    client.sendto(b"fail-closed", ("127.0.0.1", gate.port))
                upstream.settimeout(0.2)
                with self.assertRaises(TimeoutError):
                    upstream.recvfrom(64)
            finally:
                with self.assertRaisesRegex(
                    RuntimeError, "owned Hysteria cleanup failed"
                ) as raised:
                    fixture.close()
            self.assertIs(gate.error, raised.exception.__cause__.__cause__)
            self.assertEqual(-1, gate.listener.fileno())
            self.assertTrue(
                all(
                    connection.fileno() == -1
                    for connection in gate.connections.values()
                )
            )
            self.assertEqual(
                {
                    "forwarded": MAX_FLOWS * 2,
                    "dropped": 0,
                    "flows": MAX_FLOWS,
                    "flowLimit": MAX_FLOWS,
                    "errorCategory": "flow-limit",
                    "workerAlive": False,
                    "socketsClosed": True,
                },
                json.loads((root / "udp-receipts.json").read_text()),
            )

    def test_udp_gate_receipts_keep_worker_error_private(self):
        with ExitStack() as stack:
            root = Path(stack.enter_context(tempfile.TemporaryDirectory()))
            upstream, _ = self.udp_clients(stack, 0)
            gate = UdpGate("127.0.0.1", "127.0.0.1", upstream.getsockname())
            fixture = HysteriaFixture(
                root, root / "tls", root / "binary", "127.0.0.1", "127.0.0.1", {}, 1
            )
            fixture.gate = gate
            private_error = OSError("fixture-only-private-path-and-credential-marker")
            try:
                with patch.object(gate.selector, "select", side_effect=private_error):
                    gate.thread.join(timeout=3)
                    self.assertFalse(gate.thread.is_alive())
                self.assertIs(private_error, gate.error)
            finally:
                with self.assertRaisesRegex(
                    RuntimeError, "owned Hysteria cleanup failed"
                ) as raised:
                    fixture.close()
            self.assertIs(private_error, raised.exception.__cause__.__cause__)
            report = (root / "udp-receipts.json").read_text()
            self.assertNotIn(str(private_error), report)
            self.assertNotIn(fixture.password, report)
            self.assertNotIn(fixture.salamander, report)
            self.assertNotIn(str(root), report)
            self.assertEqual(
                {
                    "forwarded": 0,
                    "dropped": 0,
                    "flows": 0,
                    "flowLimit": MAX_FLOWS,
                    "errorCategory": "worker-failed",
                    "workerAlive": False,
                    "socketsClosed": True,
                },
                json.loads(report),
            )

    def test_hysteria_preparation_rejects_corrupt_download_and_keeps_existing_file(
        self,
    ):
        with tempfile.TemporaryDirectory() as temporary:
            binary = Path(temporary) / "hysteria"
            with patch(
                "android_hysteria_fixture.urllib.request.urlopen",
                return_value=io.BytesIO(b"corrupt"),
            ):
                with self.assertRaises(ValueError):
                    prepare_binary(binary)
                self.assertFalse(binary.exists())
            binary.write_bytes(b"existing-owned-by-another-run")
            with self.assertRaises(FileExistsError):
                prepare_binary(binary)
            self.assertEqual(b"existing-owned-by-another-run", binary.read_bytes())

    def test_nginx_accepts_exact_private_listener_and_rejects_public(self):
        config = configuration("127.0.0.1", 31234, "192.168.1.23:24443")
        self.assertIn("listen 192.168.1.23:24443 ssl;", config)
        self.assertIn("proxy_request_buffering off;", config)
        for subsystem in ("proxy", "fastcgi", "uwsgi", "scgi"):
            self.assertIn(f"{subsystem}_temp_path /tmp/{subsystem};", config)
        for host in (
            "0.0.0.0",
            "8.8.8.8",
            "localhost",
            "::1",
            "192.168.1.2; include /tmp/*",
        ):
            with self.subTest(host=host), self.assertRaises(ValueError):
                private_host(host)
        for listener in ("127.0.0.1:0", "127.0.0.1:65536", "127.0.0.1:80;events{}"):
            with self.subTest(listener=listener), self.assertRaises(ValueError):
                configuration("127.0.0.1", 80, listener)

    def test_nginx_keeps_private_staging_until_container_absence_is_confirmed(self):
        for unavailable in (False, True):
            with (
                self.subTest(daemon_unavailable=unavailable),
                tempfile.TemporaryDirectory() as temporary,
            ):
                root = Path(temporary)
                fixture = NginxFixture(root, root / "tls", 1234)
                stage = root / "owned-stage"
                stage.mkdir(mode=0o700)
                key = stage / "private-key.pem"
                key.write_bytes(b"owned-test-key")
                key.chmod(0o600)
                fixture.staging = stage
                fixture.container = fixture.name
                calls = 0

                def docker(*args, **kwargs):
                    nonlocal calls
                    if args[0] == "ps":
                        calls += 1
                        if calls == 1:
                            return "owned-container-id"
                        if unavailable:
                            raise subprocess.CalledProcessError(1, ["docker", "ps"])
                        return ""
                    if args[0] == "inspect":
                        return json.dumps(
                            [
                                {
                                    "Config": {
                                        "Labels": {
                                            "ripdpi.acceptance.owner": fixture.token
                                        }
                                    }
                                }
                            ]
                        )
                    self.assertEqual(args[:2], ("rm", "--force"))
                    return "owned-container-id"

                fixture.docker = docker
                with patch(
                    "android_nginx_fixture.subprocess.run",
                    return_value=SimpleNamespace(stdout=b"", stderr=b""),
                ):
                    if unavailable:
                        with self.assertRaises(subprocess.CalledProcessError):
                            fixture.close()
                        self.assertTrue(key.exists())
                        self.assertIsNotNone(fixture.container)
                    else:
                        fixture.close()
                        self.assertFalse(stage.exists())
                        self.assertIsNone(fixture.container)

    def test_udp_gate_blocks_both_directions_and_recovers_exact_peer(self):
        with (
            socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as upstream,
            socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client,
        ):
            upstream.bind(("127.0.0.1", 0))
            upstream.settimeout(1)
            client.bind(("127.0.0.1", 0))
            client.settimeout(1)
            gate = UdpGate("127.0.0.1", "127.0.0.1", upstream.getsockname())
            try:
                target = ("127.0.0.1", gate.port)
                client.sendto(b"before", target)
                packet, origin = upstream.recvfrom(64)
                self.assertEqual(b"before", packet)
                upstream.sendto(b"response", origin)
                self.assertEqual(b"response", client.recv(64))
                gate.running.clear()
                client.sendto(b"blocked", target)
                with self.assertRaises(TimeoutError):
                    upstream.recvfrom(64)
                upstream.sendto(b"blocked-response", origin)
                with self.assertRaises(TimeoutError):
                    client.recvfrom(64)
                gate.running.set()
                client.sendto(b"after", target)
                packet, origin = upstream.recvfrom(64)
                self.assertEqual(b"after", packet)
                upstream.sendto(b"recovered", origin)
                self.assertEqual(b"recovered", client.recv(64))
                self.assertGreaterEqual(gate.dropped, 2)
            finally:
                gate.close()
            self.assertFalse(gate.thread.is_alive())

    def test_udp_gate_rejects_another_source(self):
        with (
            socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as upstream,
            socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client,
        ):
            upstream.bind(("127.0.0.1", 0))
            upstream.settimeout(0.3)
            gate = UdpGate("127.0.0.1", "127.0.0.2", upstream.getsockname())
            try:
                client.sendto(b"unowned", ("127.0.0.1", gate.port))
                with self.assertRaises(TimeoutError):
                    upstream.recvfrom(64)
                self.assertEqual(1, gate.dropped)
            finally:
                gate.close()


if __name__ == "__main__":
    unittest.main()
