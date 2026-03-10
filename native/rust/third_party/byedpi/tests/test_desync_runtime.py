#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import os
import select
import shutil
import signal
import socket
import socketserver
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

from test_proxy_integration import ProxyProcess, ThreadingTCPServer, socks_connect


PROXY_BINARY = ""
PROJECT_ROOT = Path()
DESYNC_FIXTURES: dict[str, object] | None = None


def packets_corpus(name: str) -> Path:
    return PROJECT_ROOT / "tests" / "corpus" / "packets" / name


def desync_fixtures() -> dict[str, object]:
    global DESYNC_FIXTURES
    if DESYNC_FIXTURES is None:
        fixture_path = PROJECT_ROOT / "tests" / "corpus" / "rust-fixtures" / "desync_oracle.json"
        DESYNC_FIXTURES = json.loads(fixture_path.read_text())
    return DESYNC_FIXTURES


def desync_fixture(name: str) -> dict[str, object]:
    fixture = desync_fixtures().get(name)
    if not isinstance(fixture, dict):
        raise AssertionError(f"missing desync fixture: {name}")
    return fixture


class RecordingTCPServer(ThreadingTCPServer):
    def __init__(self, server_address, handler_class):
        self.streams: list[bytes] = []
        self.oob: list[bytes] = []
        self._lock = threading.Lock()
        super().__init__(server_address, handler_class)

    def record_stream(self, data: bytes) -> None:
        with self._lock:
            self.streams.append(data)

    def record_oob(self, data: bytes) -> None:
        with self._lock:
            self.oob.append(data)


class RecordingHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        self.request.settimeout(0.2)
        stream = bytearray()
        idle_loops = 0

        while idle_loops < 6:
            readable, _, exceptional = select.select([self.request], [], [self.request], 0.2)

            if exceptional:
                try:
                    urgent = self.request.recv(1, socket.MSG_OOB)
                except OSError:
                    urgent = b""
                if urgent:
                    self.server.record_oob(urgent)

            if readable:
                try:
                    data = self.request.recv(4096)
                except socket.timeout:
                    data = b""
                if not data:
                    break
                stream.extend(data)
                idle_loops = 0
            else:
                idle_loops += 1

        self.server.record_stream(bytes(stream))


class TcpdumpCapture:
    def __init__(self, port: int, packet_count: int = 16):
        self.port = port
        self.packet_count = packet_count
        self._tmpdir = tempfile.TemporaryDirectory(prefix="ciadpi-pcap-")
        self._pcap = Path(self._tmpdir.name) / "capture.pcap"
        self._proc: subprocess.Popen[str] | None = None

    @staticmethod
    def supported() -> bool:
        if not sys.platform.startswith("linux"):
            return False
        if not shutil.which("tcpdump"):
            return False
        if os.geteuid() == 0:
            return True
        if not shutil.which("sudo"):
            return False
        return subprocess.run(
            ["sudo", "-n", "true"], capture_output=True, check=False
        ).returncode == 0

    @property
    def pcap_path(self) -> Path:
        return self._pcap

    def start(self) -> None:
        self._proc = subprocess.Popen(
            [
                "sudo",
                "-n",
                "tcpdump",
                "-i",
                "lo",
                "-s",
                "0",
                "-U",
                "-w",
                str(self._pcap),
                "-c",
                str(self.packet_count),
                "tcp",
                "and",
                "port",
                str(self.port),
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            text=True,
        )
        deadline = time.time() + 2
        while time.time() < deadline:
            if self._pcap.exists():
                return
            time.sleep(0.05)

    def stop(self) -> None:
        if not self._proc:
            return
        try:
            self._proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            self._proc.send_signal(getattr(signal, "SIGINT", 2))
            try:
                self._proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self._proc.terminate()
                self._proc.wait(timeout=2)

    def cleanup(self) -> None:
        self._tmpdir.cleanup()

    def __enter__(self) -> "TcpdumpCapture":
        self.start()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.stop()
        self.cleanup()


def parse_tcp_options(options: bytes) -> list[tuple[int, bytes]]:
    parsed: list[tuple[int, bytes]] = []
    idx = 0
    while idx < len(options):
        kind = options[idx]
        if kind == 0:
            parsed.append((0, b""))
            break
        if kind == 1:
            parsed.append((1, b""))
            idx += 1
            continue
        if idx + 1 >= len(options):
            break
        size = options[idx + 1]
        if size < 2 or idx + size > len(options):
            break
        parsed.append((kind, options[idx + 2 : idx + size]))
        idx += size
    return parsed


def parse_pcap_packets(path: Path) -> list[dict[str, object]]:
    data = path.read_bytes()
    if len(data) < 24:
        return []

    magic = data[:4]
    if magic == b"\xd4\xc3\xb2\xa1":
        endian = "<"
    elif magic == b"\xa1\xb2\xc3\xd4":
        endian = ">"
    else:
        raise AssertionError(f"unsupported pcap magic: {magic.hex()}")

    _, _, _, _, _, linktype = struct.unpack(endian + "HHIIII", data[4:24])
    offset = 24
    packets: list[dict[str, object]] = []

    while offset + 16 <= len(data):
        ts_sec, ts_usec, incl_len, _ = struct.unpack(endian + "IIII", data[offset : offset + 16])
        offset += 16
        frame = data[offset : offset + incl_len]
        offset += incl_len

        if linktype == 1:
            l2_len = 14
        elif linktype == 113:
            l2_len = 16
        elif linktype == 276:
            l2_len = 20
        else:
            continue
        if len(frame) <= l2_len:
            continue

        ip = frame[l2_len:]
        version = ip[0] >> 4
        if version != 4:
            continue
        ihl = (ip[0] & 0x0F) * 4
        if len(ip) < ihl + 20:
            continue
        total_len = struct.unpack("!H", ip[2:4])[0]
        ttl = ip[8]
        if ip[9] != socket.IPPROTO_TCP:
            continue

        tcp = ip[ihl:]
        src_port, dst_port = struct.unpack("!HH", tcp[:4])
        data_offset = ((tcp[12] >> 4) & 0x0F) * 4
        flags = tcp[13]
        payload = tcp[data_offset:total_len - ihl]
        options = tcp[20:data_offset] if data_offset > 20 else b""

        packets.append(
            {
                "ts": ts_sec + (ts_usec / 1_000_000),
                "src_port": src_port,
                "dst_port": dst_port,
                "ttl": ttl,
                "flags": flags,
                "payload": payload,
                "options": parse_tcp_options(options),
            }
        )

    return packets


def has_urg(flags: int) -> bool:
    return bool(flags & 0x20)


def first_outbound_payload_packets(packets: list[dict[str, object]], dst_port: int) -> list[dict[str, object]]:
    return [
        packet
        for packet in packets
        if packet["dst_port"] == dst_port and len(packet["payload"]) > 0
    ]


class DesyncRuntimeTests(unittest.TestCase):
    def setUp(self) -> None:
        self._servers: list[socketserver.TCPServer] = []
        self._threads: list[threading.Thread] = []

    def tearDown(self) -> None:
        for server in self._servers:
            server.shutdown()
            server.server_close()

    def _start_server(self, server: socketserver.TCPServer) -> socketserver.TCPServer:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self._servers.append(server)
        self._threads.append(thread)
        return server

    def _recording_server(self) -> RecordingTCPServer:
        return self._start_server(RecordingTCPServer(("127.0.0.1", 0), RecordingHandler))

    def _send_once(self, payload: bytes, extra_args: list[str]) -> RecordingTCPServer:
        server = self._recording_server()
        with ProxyProcess(PROXY_BINARY, extra_args=extra_args) as proxy:
            with socks_connect(proxy.port, server.server_address[1]) as sock:
                sock.sendall(payload)
                sock.shutdown(socket.SHUT_WR)
                time.sleep(0.2)
        deadline = time.time() + 2
        while time.time() < deadline:
            if server.streams:
                break
            time.sleep(0.05)
        return server

    def test_mod_http_runtime_matches_fixture(self) -> None:
        payload = packets_corpus("http_request.bin").read_bytes()
        expected = bytes.fromhex(str(desync_fixture("runtime_mod_http")["tampered_hex"]))
        server = self._send_once(payload, ["--mod-http", "rh"])
        self.assertEqual(server.streams[0], expected)

    def test_tlsminor_runtime_matches_fixture(self) -> None:
        payload = packets_corpus("tls_client_hello.bin").read_bytes()
        expected = bytes.fromhex(str(desync_fixture("runtime_tlsminor")["tampered_hex"]))
        server = self._send_once(payload, ["--tlsminor", "5"])
        self.assertEqual(server.streams[0], expected)

    def test_tlsrec_runtime_matches_fixture(self) -> None:
        payload = packets_corpus("tls_client_hello.bin").read_bytes()
        expected = bytes.fromhex(str(desync_fixture("runtime_tlsrec")["tampered_hex"]))
        server = self._send_once(payload, ["--tlsrec", "32"])
        self.assertEqual(server.streams[0], expected)

    def test_oob_runtime_preserves_stream_and_delivers_urgent_byte(self) -> None:
        payload = packets_corpus("http_request.bin").read_bytes()
        server = self._send_once(payload, ["--oob", "8", "--oob-data", "Z"])
        self.assertEqual(server.streams[0], payload)
        self.assertIn(b"Z", server.oob)


@unittest.skipUnless(TcpdumpCapture.supported(), "Linux tcpdump capture is unavailable")
class LinuxWireCaptureTests(unittest.TestCase):
    def setUp(self) -> None:
        self._servers: list[socketserver.TCPServer] = []
        self._threads: list[threading.Thread] = []

    def tearDown(self) -> None:
        for server in self._servers:
            server.shutdown()
            server.server_close()

    def _start_server(self, server: socketserver.TCPServer) -> socketserver.TCPServer:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self._servers.append(server)
        self._threads.append(thread)
        return server

    def _capture_payloads(self, payload: bytes, extra_args: list[str]) -> list[dict[str, object]]:
        server = self._start_server(RecordingTCPServer(("127.0.0.1", 0), RecordingHandler))
        capture = TcpdumpCapture(server.server_address[1])
        capture.start()
        try:
            with ProxyProcess(PROXY_BINARY, extra_args=extra_args) as proxy:
                with socks_connect(proxy.port, server.server_address[1]) as sock:
                    sock.sendall(payload)
                    sock.shutdown(socket.SHUT_WR)
                    time.sleep(0.5)
            capture.stop()
            packets = parse_pcap_packets(capture.pcap_path)
        finally:
            capture.cleanup()
        return first_outbound_payload_packets(packets, server.server_address[1])

    def test_split_emits_separate_payload_chunks(self) -> None:
        payload = packets_corpus("http_request.bin").read_bytes()
        packets = self._capture_payloads(payload, ["--split", "8"])
        self.assertGreaterEqual(len(packets), 2)
        self.assertEqual(packets[0]["payload"], payload[:8])
        self.assertEqual(packets[1]["payload"], payload[8:])

    def test_oob_sets_urg_flag_and_custom_byte(self) -> None:
        payload = packets_corpus("http_request.bin").read_bytes()
        packets = self._capture_payloads(payload, ["--oob", "8", "--oob-data", "Z"])
        urg_packets = [packet for packet in packets if has_urg(int(packet["flags"]))]
        self.assertTrue(urg_packets)
        self.assertEqual(urg_packets[0]["payload"], payload[:8] + b"Z")

    def test_disorder_marks_first_chunk_with_ttl_one(self) -> None:
        payload = packets_corpus("http_request.bin").read_bytes()
        packets = self._capture_payloads(payload, ["--disorder", "8"])
        self.assertTrue(packets)
        self.assertEqual(packets[0]["ttl"], 1)
        self.assertEqual(packets[0]["payload"], payload[:8])

    def test_disoob_marks_urgent_chunk_with_ttl_one(self) -> None:
        payload = packets_corpus("http_request.bin").read_bytes()
        packets = self._capture_payloads(payload, ["--disoob", "8", "--oob-data", "Z"])
        urg_packets = [packet for packet in packets if has_urg(int(packet["flags"]))]
        self.assertTrue(urg_packets)
        self.assertEqual(urg_packets[0]["ttl"], 1)
        self.assertEqual(urg_packets[0]["payload"], payload[:8] + b"Z")

    def test_fake_sends_custom_fake_prefix_with_custom_ttl(self) -> None:
        payload = packets_corpus("http_request.bin").read_bytes()
        fake_payload = b"GET /f HTTP/1.1\r\nHost: fake.example.test\r\n\r\n"
        packets = self._capture_payloads(
            payload,
            [
                "--fake",
                "8",
                "--ttl",
                "3",
                "--fake-data",
                ":" + fake_payload.decode("ascii"),
            ],
        )
        self.assertGreaterEqual(len(packets), 2)
        self.assertEqual(packets[0]["ttl"], 3)
        self.assertEqual(packets[0]["payload"], fake_payload[:8])
        self.assertEqual(packets[1]["payload"], payload[8:])

    def test_wait_send_delays_split_chunks(self) -> None:
        payload = packets_corpus("http_request.bin").read_bytes()
        packets = self._capture_payloads(
            payload,
            ["--split", "8", "--wait-send", "--await-int", "40"],
        )
        self.assertGreaterEqual(len(packets), 2)
        self.assertEqual(packets[0]["payload"], payload[:8])
        self.assertEqual(packets[1]["payload"], payload[8:])
        self.assertGreaterEqual(packets[1]["ts"] - packets[0]["ts"], 0.02)

    def test_wait_send_delays_fake_retransmit(self) -> None:
        payload = packets_corpus("http_request.bin").read_bytes()
        fake_payload = b"GET /f HTTP/1.1\r\nHost: fake.example.test\r\n\r\n"
        packets = self._capture_payloads(
            payload,
            [
                "--fake",
                "8",
                "--wait-send",
                "--await-int",
                "40",
                "--fake-data",
                ":" + fake_payload.decode("ascii"),
            ],
        )
        self.assertGreaterEqual(len(packets), 2)
        self.assertEqual(packets[0]["payload"], fake_payload[:8])
        self.assertEqual(packets[1]["payload"], payload[8:])
        self.assertGreaterEqual(packets[1]["ts"] - packets[0]["ts"], 0.02)

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    parser.add_argument("--project-root", required=True)
    args = parser.parse_args()

    global PROXY_BINARY, PROJECT_ROOT
    PROXY_BINARY = args.binary
    PROJECT_ROOT = Path(args.project_root).resolve()

    suite = unittest.TestSuite()
    for case in (DesyncRuntimeTests, LinuxWireCaptureTests):
        suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(case))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
