import importlib.util
from pathlib import Path
import socket
import struct
import threading
import time
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "ci/android_udp_bridge.py"
SPEC = importlib.util.spec_from_file_location("android_udp_bridge", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class EchoPeer:
    def __init__(self, port=0):
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.bind(("127.0.0.1", port))
        self.socket.settimeout(0.1)
        self.port = self.socket.getsockname()[1]
        self.received = []
        self.stopped = threading.Event()
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()

    def serve(self):
        while not self.stopped.is_set():
            try:
                packet, source = self.socket.recvfrom(65535)
                self.received.append(packet)
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
        connection = socket.create_connection(("127.0.0.1", self.bridge.port), timeout=3)
        self.addCleanup(connection.close)
        connection.sendall(bytes.fromhex(self.bridge.token) if token is None else token)
        return connection

    def receive(self, connection):
        stop = threading.Event()
        header = MODULE.receive_exact(connection, 2, stop, time.monotonic() + 3)
        self.assertIsNotNone(header)
        return MODULE.receive_exact(connection, struct.unpack("!H", header)[0], stop, time.monotonic() + 3)

    def send(self, connection, packet):
        connection.sendall(struct.pack("!H", len(packet)) + packet)

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
        frames = b"\x00\x03one\x00\x03two"
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
        while self.bridge.receipts()["peerRefused"] == 0 and time.monotonic() < deadline:
            time.sleep(0.01)
        self.peer = EchoPeer(port)
        self.send(connection, b"recovered")
        self.assertEqual(b"recovered", self.receive(connection))
        self.assertEqual([b"recovered"], self.peer.received)
        self.assertEqual(1, self.bridge.receipts()["connections"])

    def test_malformed_length_refuses_without_udp_delivery(self):
        connection = self.connect()
        connection.sendall(b"\x00\x00")
        self.assertEqual(b"", connection.recv(1))
        self.assertEqual([], self.peer.received)
        with self.assertRaisesRegex(RuntimeError, "transport failed"):
            self.bridge.close()

    def test_truncated_frame_refuses_without_udp_delivery(self):
        connection = self.connect()
        connection.sendall(b"\x00\x09short")
        connection.shutdown(socket.SHUT_WR)
        self.assertEqual(b"", connection.recv(1))
        self.assertEqual([], self.peer.received)
        with self.assertRaisesRegex(RuntimeError, "transport failed"):
            self.bridge.close()

    def test_above_wire_maximum_refuses_before_udp_send(self):
        connection = self.connect()
        connection.sendall(struct.pack("!H", MODULE.MAX_DATAGRAM + 1))
        self.assertEqual(b"", connection.recv(1))
        self.assertEqual([], self.peer.received)
        with self.assertRaisesRegex(RuntimeError, "transport failed"):
            self.bridge.close()

    def test_cleanup_cancels_partial_auth_and_closes_listener(self):
        connection = socket.create_connection(("127.0.0.1", self.bridge.port), timeout=3)
        self.addCleanup(connection.close)
        connection.sendall(b"partial")
        self.bridge.close()
        self.bridge.close()
        with self.assertRaises(OSError):
            socket.create_connection(("127.0.0.1", self.bridge.port), timeout=1)

    def test_remote_and_invalid_peers_are_refused(self):
        for port, host in ((0, "127.0.0.1"), (True, "127.0.0.1"), (65536, "127.0.0.1"), (80, "192.0.2.1")):
            with self.assertRaises(ValueError):
                MODULE.UdpBridge(port, host)


if __name__ == "__main__":
    unittest.main()
