import importlib.util
from pathlib import Path
import socket
import struct
import threading
import time
import unittest
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "ci/android_udp_bridge.py"
SPEC = importlib.util.spec_from_file_location("android_udp_bridge", SCRIPT)
FRAME = struct.Struct("!BIH")
VERSION = 1
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class EchoPeer:
    def __init__(self, port=0):
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.bind(("127.0.0.1", port))
        self.socket.settimeout(0.1)
        self.port = self.socket.getsockname()[1]
        self.received = []
        self.sources = []
        self.changed = threading.Condition()
        self.reply_enabled = True
        self.stopped = threading.Event()
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()

    def serve(self):
        while not self.stopped.is_set():
            try:
                packet, source = self.socket.recvfrom(65535)
                with self.changed:
                    self.received.append(packet)
                    self.sources.append(source)
                    self.changed.notify_all()
                if self.reply_enabled:
                    self.socket.sendto(packet, source)
            except socket.timeout:
                continue
            except OSError:
                if not self.stopped.is_set():
                    raise

    def close(self):
        self.stopped.set()
        self.thread.join(timeout=2)
        if self.thread.is_alive():
            raise RuntimeError("test echo peer did not stop")
        self.socket.close()


class AndroidUdpBridgeTest(unittest.TestCase):
    def setUp(self):
        self.peer = EchoPeer()
        self.addCleanup(lambda: self.peer.close())
        self.bridge = MODULE.UdpBridge(self.peer.port)
        self.bridge.start()
        self.addCleanup(self.bridge.close)

    def connect(self, token=None):
        connection = socket.create_connection(
            ("127.0.0.1", self.bridge.port), timeout=3
        )
        self.addCleanup(connection.close)
        connection.sendall(bytes.fromhex(self.bridge.token) if token is None else token)
        return connection

    def assert_closed(self, connection):
        try:
            self.assertEqual(b"", connection.recv(1))
        except ConnectionResetError:
            # Rejecting a header before unread payload can produce TCP RST.
            pass

    def receive_flow(self, connection):
        stop = threading.Event()
        header = MODULE.receive_exact(
            connection, FRAME.size, stop, time.monotonic() + 3
        )
        self.assertIsNotNone(header)
        version, flow_id, size = FRAME.unpack(header)
        self.assertEqual(VERSION, version)
        return flow_id, MODULE.receive_exact(
            connection, size, stop, time.monotonic() + 3
        )

    def receive(self, connection):
        flow_id, packet = self.receive_flow(connection)
        self.assertEqual(1, flow_id)
        return packet

    def send(self, connection, packet, flow_id=1):
        connection.sendall(FRAME.pack(VERSION, flow_id, len(packet)) + packet)

    def test_concurrent_flows_keep_delayed_reverse_order_replies_separate(self):
        self.peer.reply_enabled = False
        connection = self.connect()
        self.send(connection, b"first-source", flow_id=1)
        self.send(connection, b"second-source", flow_id=2)
        with self.peer.changed:
            self.assertTrue(
                self.peer.changed.wait_for(
                    lambda: len(self.peer.received) == 2, timeout=3
                )
            )
        self.assertNotEqual(self.peer.sources[0], self.peer.sources[1])
        self.peer.socket.sendto(b"second-reply", self.peer.sources[1])
        self.assertEqual((2, b"second-reply"), self.receive_flow(connection))
        self.peer.socket.sendto(b"first-reply", self.peer.sources[0])
        self.assertEqual((1, b"first-reply"), self.receive_flow(connection))
        self.send(connection, b"first-again", flow_id=1)
        with self.peer.changed:
            self.assertTrue(
                self.peer.changed.wait_for(
                    lambda: len(self.peer.received) == 3, timeout=3
                )
            )
        self.assertEqual(self.peer.sources[0], self.peer.sources[2])
        self.assertEqual(2, self.bridge.receipts()["flows"])

    def test_exact_datagrams_cross_real_udp_and_framed_tcp(self):
        connection = self.connect()
        # macOS has a lower kernel UDP maximum than the IPv4 wire maximum.
        # This covers the supported VPN MTUs without changing host settings.
        packets = [b"one", bytes(range(256)) * 6, b"x" * 8192]
        for packet in packets:
            self.send(connection, packet)
            self.assertEqual(packet, self.receive(connection))
        self.assertEqual(packets, self.peer.received)
        receipt = self.bridge.receipts()
        self.assertEqual("usb-datagram", receipt["scope"])
        self.assertEqual(3, receipt["datagramsToPeer"])
        self.assertEqual(sum(map(len, packets)), receipt["bytesToPeer"])
        self.assertNotIn(self.bridge.token, str(receipt))

    def test_split_and_combined_frames_preserve_packet_boundaries(self):
        connection = self.connect()
        frames = FRAME.pack(VERSION, 1, 3) + b"one" + FRAME.pack(VERSION, 1, 3) + b"two"
        for chunk in (frames[:1], frames[1:4], frames[4:]):
            connection.sendall(chunk)
        self.assertEqual(b"one", self.receive(connection))
        self.assertEqual(b"two", self.receive(connection))
        self.assertEqual([b"one", b"two"], self.peer.received)

    def test_wrong_token_never_reaches_udp_peer(self):
        wrong = self.connect(b"\x00" * 32)
        self.assertEqual(b"", wrong.recv(1))
        self.assertEqual([], self.peer.received)
        self.assertEqual(1, self.bridge.receipts()["unauthorized"])
        valid = self.connect()
        self.send(valid, b"accepted")
        self.assertEqual(b"accepted", self.receive(valid))

    def test_stopped_peer_does_not_stop_independent_carrier(self):
        connection = self.connect()
        self.send(connection, b"before")
        self.assertEqual(b"before", self.receive(connection))
        port = self.peer.port
        self.peer.close()
        self.send(connection, b"while-down")
        deadline = time.monotonic() + 1
        while (
            self.bridge.receipts()["peerRefused"] == 0 and time.monotonic() < deadline
        ):
            time.sleep(0.01)
        self.peer = EchoPeer(port)
        self.send(connection, b"recovered")
        self.assertEqual(b"recovered", self.receive(connection))
        self.assertEqual([b"recovered"], self.peer.received)
        self.assertEqual(1, self.bridge.receipts()["connections"])

    def test_malformed_length_refuses_without_udp_delivery(self):
        connection = self.connect()
        connection.sendall(FRAME.pack(VERSION, 1, 0))
        self.assertEqual(b"", connection.recv(1))
        self.assertEqual([], self.peer.received)
        with self.assertRaisesRegex(RuntimeError, "transport failed"):
            self.bridge.close()

    def test_truncated_frame_refuses_without_udp_delivery(self):
        connection = self.connect()
        connection.sendall(FRAME.pack(VERSION, 1, 9) + b"short")
        connection.shutdown(socket.SHUT_WR)
        self.assertEqual(b"", connection.recv(1))
        self.assertEqual([], self.peer.received)
        with self.assertRaisesRegex(RuntimeError, "transport failed"):
            self.bridge.close()

    def test_cleanup_after_peer_eof_retains_the_protocol_error(self):
        connection_closed = threading.Event()
        finish_close = threading.Event()
        original_exit = socket.socket.__exit__
        errors = []

        def delayed_exit(connection, *arguments):
            result = original_exit(connection, *arguments)
            if connection is self.bridge._connection:
                connection_closed.set()
                finish_close.wait(timeout=3)
            return result

        def close_bridge():
            try:
                self.bridge.close()
            except Exception as error:
                errors.append(error)

        closer = threading.Thread(target=close_bridge)
        with mock.patch.object(socket.socket, "__exit__", delayed_exit):
            connection = self.connect()
            connection.sendall(FRAME.pack(VERSION, 1, 9) + b"short")
            connection.shutdown(socket.SHUT_WR)
            try:
                self.assertEqual(b"", connection.recv(1))
                self.assertTrue(connection_closed.wait(timeout=1))
                closer.start()
                self.assertTrue(self.bridge._stop.wait(timeout=1))
            finally:
                finish_close.set()
                if closer.ident is not None:
                    closer.join(timeout=3)
        self.assertFalse(closer.is_alive())
        self.assertEqual([], self.peer.received)
        self.assertEqual(1, len(errors))
        self.assertIsInstance(errors[0], RuntimeError)
        self.assertIsInstance(errors[0].__cause__, ValueError)

    def test_above_wire_maximum_refuses_before_udp_send(self):
        connection = self.connect()
        connection.sendall(FRAME.pack(VERSION, 1, MODULE.MAX_DATAGRAM + 1))
        self.assertEqual(b"", connection.recv(1))
        self.assertEqual([], self.peer.received)
        with self.assertRaisesRegex(RuntimeError, "transport failed"):
            self.bridge.close()

    def test_cleanup_cancels_partial_auth_and_closes_listener(self):
        connection = socket.create_connection(
            ("127.0.0.1", self.bridge.port), timeout=3
        )
        self.addCleanup(connection.close)
        connection.sendall(b"partial")
        self.bridge.close()
        self.bridge.close()
        with self.assertRaises(OSError):
            socket.create_connection(("127.0.0.1", self.bridge.port), timeout=1)

    def test_version_and_zero_flow_are_rejected_before_socket_allocation(self):
        for version, flow_id in ((0, 1), (2, 1), (VERSION, 0)):
            with self.subTest(version=version, flow_id=flow_id):
                bridge = MODULE.UdpBridge(self.peer.port)
                bridge.start()
                with socket.create_connection(
                    ("127.0.0.1", bridge.port), timeout=3
                ) as connection:
                    connection.sendall(
                        bytes.fromhex(bridge.token)
                        + FRAME.pack(version, flow_id, 1)
                        + b"x"
                    )
                    self.assert_closed(connection)
                self.assertEqual(0, bridge.receipts()["flows"])
                with self.assertRaisesRegex(RuntimeError, "transport failed"):
                    bridge.close()
                bridge.close()
        self.assertEqual([], self.peer.received)

    def test_flow_bound_refuses_extra_source_and_closes_all_owned_sockets(self):
        connection = self.connect()
        for flow_id in range(1, MODULE.MAX_FLOWS + 1):
            self.send(connection, b"owned", flow_id=flow_id)
            self.assertEqual((flow_id, b"owned"), self.receive_flow(connection))
        sources = set(self.peer.sources)
        self.assertEqual(MODULE.MAX_FLOWS, len(sources))
        self.send(connection, b"over-bound", flow_id=MODULE.MAX_FLOWS + 1)
        self.assert_closed(connection)
        self.assertNotIn(b"over-bound", self.peer.received)
        with self.assertRaisesRegex(RuntimeError, "transport failed"):
            self.bridge.close()
        self.assertFalse(self.bridge._thread.is_alive())
        for source in sources:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as released:
                released.bind(source)

    def test_wrong_token_allocates_no_flows(self):
        connection = self.connect(b"\x00" * 32)
        connection.sendall(FRAME.pack(VERSION, 1, 1) + b"x")
        self.assert_closed(connection)
        self.assertEqual(0, self.bridge.receipts()["flows"])
        self.assertEqual([], self.peer.received)

    def test_legacy_length_only_frames_are_not_accepted(self):
        connection = self.connect()
        connection.sendall(b"\x00\x03one")
        connection.shutdown(socket.SHUT_WR)
        self.assert_closed(connection)
        self.assertEqual(0, self.bridge.receipts()["flows"])
        with self.assertRaisesRegex(RuntimeError, "transport failed"):
            self.bridge.close()

    def test_lifetime_limit_stops_idle_authenticated_connection(self):
        bridge = MODULE.UdpBridge(self.peer.port)
        with mock.patch.object(MODULE, "MAX_LIFETIME_SECONDS", 0.05):
            bridge.start()
        with socket.create_connection(
            ("127.0.0.1", bridge.port), timeout=3
        ) as connection:
            connection.sendall(bytes.fromhex(bridge.token))
            self.assert_closed(connection)
        with self.assertRaisesRegex(RuntimeError, "transport failed") as failure:
            bridge.close()
        self.assertIsInstance(failure.exception.__cause__, TimeoutError)
        self.assertFalse(bridge._thread.is_alive())
        self.assertEqual(0, bridge.receipts()["flows"])

    def test_remote_and_invalid_peers_are_refused(self):
        for port, host in (
            (0, "127.0.0.1"),
            (True, "127.0.0.1"),
            (65536, "127.0.0.1"),
            (80, "192.0.2.1"),
        ):
            with self.assertRaises(ValueError):
                MODULE.UdpBridge(port, host)


if __name__ == "__main__":
    unittest.main()
