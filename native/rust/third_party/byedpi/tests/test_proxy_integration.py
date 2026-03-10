#!/usr/bin/env python3

from __future__ import annotations

import argparse
import os
import shutil
import socket
import socketserver
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path


PROXY_BINARY = ""


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def default_conn_ip() -> str:
    if not socket.has_ipv6:
        return "0.0.0.0"
    probe = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
    try:
        probe.bind(("::1", 0))
    except OSError:
        return "0.0.0.0"
    finally:
        probe.close()
    return "::"


def recv_exact(sock: socket.socket, size: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        chunk = sock.recv(size - len(chunks))
        if not chunk:
            raise AssertionError(f"socket closed after {len(chunks)} bytes")
        chunks.extend(chunk)
    return bytes(chunks)


def recv_until(sock: socket.socket, marker: bytes) -> bytes:
    chunks = bytearray()
    while marker not in chunks:
        chunk = sock.recv(4096)
        if not chunk:
            raise AssertionError("socket closed before marker arrived")
        chunks.extend(chunk)
    return bytes(chunks)


class ThreadingTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


class ThreadingTCPServerV6(ThreadingTCPServer):
    address_family = socket.AF_INET6


class ThreadingUDPServer(socketserver.ThreadingMixIn, socketserver.UDPServer):
    allow_reuse_address = True
    daemon_threads = True


class RecordingUDPServer(socketserver.UDPServer):
    allow_reuse_address = True

    def __init__(self, server_address, handler_class):
        self.packets: list[bytes] = []
        self._lock = threading.Lock()
        super().__init__(server_address, handler_class)

    def record(self, data: bytes) -> None:
        with self._lock:
            self.packets.append(data)


class EchoHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        while True:
            data = self.request.recv(4096)
            if not data:
                return
            self.request.sendall(data)


class UDPEchoHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        data, sock = self.request
        if hasattr(self.server, "record"):
            self.server.record(data)
        sock.sendto(data, self.client_address)


class TLSHTTPHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        request = recv_until(self.request, b"\r\n\r\n")
        if b"GET / HTTP/1.1" not in request:
            raise AssertionError("unexpected TLS request payload")
        self.request.sendall(
            b"HTTP/1.1 200 OK\r\n"
            b"Content-Length: 12\r\n"
            b"Connection: close\r\n"
            b"\r\n"
            b"proxy tls ok"
        )


class TLSServer(ThreadingTCPServer):
    def __init__(self, certfile: str, keyfile: str):
        self._context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self._context.load_cert_chain(certfile=certfile, keyfile=keyfile)
        super().__init__(("127.0.0.1", 0), TLSHTTPHandler)

    def get_request(self):
        sock, addr = super().get_request()
        return self._context.wrap_socket(sock, server_side=True), addr


def generate_localhost_cert(tempdir: Path) -> tuple[Path, Path]:
    certfile = tempdir / "cert.pem"
    keyfile = tempdir / "key.pem"
    openssl = shutil.which("openssl")
    if openssl is None:
        raise unittest.SkipTest("openssl is unavailable")

    cmd = [
        openssl,
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-sha256",
        "-nodes",
        "-keyout",
        str(keyfile),
        "-out",
        str(certfile),
        "-days",
        "1",
        "-subj",
        "/CN=localhost",
        "-addext",
        "subjectAltName=DNS:localhost,IP:127.0.0.1",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise AssertionError(f"openssl failed to generate localhost cert:\n{result.stderr}")
    return certfile, keyfile


class ProxyProcess:
    def __init__(
        self,
        binary: str,
        http_connect: bool = False,
        extra_args: list[str] | None = None,
        env_updates: dict[str, str] | None = None,
    ):
        self.binary = binary
        self.http_connect = http_connect
        self.extra_args = extra_args or []
        self.env_updates = env_updates or {}
        self.port = free_port()
        self._log = tempfile.NamedTemporaryFile(prefix="ciadpi-", suffix=".log", delete=False)
        self._proc: subprocess.Popen[str] | None = None

    def start(self) -> None:
        args = [
            self.binary,
            "-i",
            "127.0.0.1",
            "-p",
            str(self.port),
            "-I",
            default_conn_ip(),
        ]
        if self.http_connect:
            args.append("-G")
        args.extend(self.extra_args)

        env = os.environ.copy()
        env.setdefault("ASAN_OPTIONS", "detect_leaks=0")
        env.update(self.env_updates)
        self._proc = subprocess.Popen(
            args,
            stdout=self._log,
            stderr=subprocess.STDOUT,
            text=True,
            env=env,
        )
        deadline = time.time() + 5
        while time.time() < deadline:
            try:
                with socket.create_connection(("127.0.0.1", self.port), timeout=0.2):
                    return
            except OSError:
                if self._proc.poll() is not None:
                    raise AssertionError(f"proxy exited early:\n{self.read_log()}")
                time.sleep(0.05)
        raise AssertionError(f"proxy did not start:\n{self.read_log()}")

    def stop(self) -> None:
        if not self._proc:
            return
        if self._proc.poll() is None:
            self._proc.terminate()
            try:
                self._proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._proc.kill()
                self._proc.wait(timeout=5)
        self._log.close()
        Path(self._log.name).unlink(missing_ok=True)

    def read_log(self) -> str:
        self._log.flush()
        return Path(self._log.name).read_text() if Path(self._log.name).exists() else ""

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, exc_type, exc, tb):
        self.stop()


def recv_socks5_reply(sock: socket.socket) -> bytes:
    header = recv_exact(sock, 4)
    atyp = header[3]
    if atyp == 0x01:
        tail = recv_exact(sock, 6)
    elif atyp == 0x04:
        tail = recv_exact(sock, 18)
    elif atyp == 0x03:
        size = recv_exact(sock, 1)[0]
        tail = bytes([size]) + recv_exact(sock, size + 2)
    else:
        raise AssertionError(f"unsupported SOCKS5 reply ATYP: {atyp}")
    return header + tail


def parse_socks5_reply(reply: bytes) -> tuple[int, str, int]:
    atyp = reply[3]
    if atyp == 0x01:
        return reply[1], socket.inet_ntoa(reply[4:8]), int.from_bytes(reply[8:10], "big")
    if atyp == 0x04:
        return (
            reply[1],
            socket.inet_ntop(socket.AF_INET6, reply[4:20]),
            int.from_bytes(reply[20:22], "big"),
        )
    if atyp == 0x03:
        size = reply[4]
        host = reply[5 : 5 + size].decode("ascii")
        port = int.from_bytes(reply[5 + size : 7 + size], "big")
        return reply[1], host, port
    raise AssertionError(f"unsupported SOCKS5 reply ATYP: {atyp}")


def socks_auth(proxy_port: int) -> socket.socket:
    sock = socket.create_connection(("127.0.0.1", proxy_port), timeout=5)
    sock.sendall(b"\x05\x01\x00")
    if recv_exact(sock, 2) != b"\x05\x00":
        raise AssertionError("SOCKS5 auth negotiation failed")
    return sock


def socks_connect(proxy_port: int, dst_port: int) -> socket.socket:
    sock = socks_auth(proxy_port)
    request = b"\x05\x01\x00\x01" + socket.inet_aton("127.0.0.1") + dst_port.to_bytes(2, "big")
    sock.sendall(request)
    reply = recv_socks5_reply(sock)
    if parse_socks5_reply(reply)[0] != 0:
        raise AssertionError(f"SOCKS5 connect failed: {reply!r}")
    return sock


def socks4_connect(proxy_port: int, dst_port: int) -> socket.socket:
    sock = socket.create_connection(("127.0.0.1", proxy_port), timeout=5)
    request = b"\x04\x01" + dst_port.to_bytes(2, "big") + socket.inet_aton("127.0.0.1") + b"user\x00"
    sock.sendall(request)
    reply = recv_exact(sock, 8)
    if reply[1] != 0x5A:
        raise AssertionError(f"SOCKS4 connect failed: {reply!r}")
    return sock


def socks_connect_domain(proxy_port: int, host: str, dst_port: int) -> tuple[socket.socket, bytes]:
    sock = socks_auth(proxy_port)
    host_bytes = host.encode("ascii")
    request = b"\x05\x01\x00\x03" + bytes([len(host_bytes)]) + host_bytes + dst_port.to_bytes(2, "big")
    sock.sendall(request)
    return sock, recv_socks5_reply(sock)


def socks_connect_ipv6(proxy_port: int, host: str, dst_port: int) -> socket.socket:
    sock = socks_auth(proxy_port)
    request = (
        b"\x05\x01\x00\x04"
        + socket.inet_pton(socket.AF_INET6, host)
        + dst_port.to_bytes(2, "big")
    )
    sock.sendall(request)
    reply = recv_socks5_reply(sock)
    if parse_socks5_reply(reply)[0] != 0:
        raise AssertionError(f"SOCKS5 IPv6 connect failed: {reply!r}")
    return sock


def socks_udp_associate(proxy_port: int) -> tuple[socket.socket, tuple[str, int]]:
    sock = socks_auth(proxy_port)
    request = b"\x05\x03\x00\x01" + socket.inet_aton("0.0.0.0") + b"\x00\x00"
    sock.sendall(request)
    reply = recv_socks5_reply(sock)
    code, relay_host, relay_port = parse_socks5_reply(reply)
    if code != 0:
        raise AssertionError(f"SOCKS5 UDP associate failed: {reply!r}")
    return sock, (relay_host, relay_port)


def udp_proxy_roundtrip(relay: tuple[str, int], dst_port: int, payload: bytes) -> bytes:
    udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp_sock.settimeout(5)
    try:
        packet = b"\x00\x00\x00\x01" + socket.inet_aton("127.0.0.1") + dst_port.to_bytes(2, "big") + payload
        udp_sock.sendto(packet, relay)
        deadline = time.time() + 5
        while time.time() < deadline:
            response, _ = udp_sock.recvfrom(4096)
            if response[:4] != b"\x00\x00\x00\x01":
                raise AssertionError(f"unexpected UDP header: {response!r}")
            body = response[10:]
            if body == payload:
                return body
        raise AssertionError("timed out waiting for expected UDP payload")
    finally:
        udp_sock.close()


def http_connect(proxy_port: int, dst_port: int) -> socket.socket:
    sock = socket.create_connection(("127.0.0.1", proxy_port), timeout=5)
    sock.sendall(
        (
            f"CONNECT 127.0.0.1:{dst_port} HTTP/1.1\r\n"
            f"Host: 127.0.0.1:{dst_port}\r\n"
            "\r\n"
        ).encode("ascii")
    )
    response = recv_until(sock, b"\r\n\r\n")
    if b"HTTP/1.1 200 OK" not in response:
        raise AssertionError(f"HTTP CONNECT failed: {response!r}")
    return sock


class ProxyIntegrationTests(unittest.TestCase):
    def setUp(self) -> None:
        self._servers = []
        self._threads = []
        self._tmpdir = tempfile.TemporaryDirectory(prefix="ciadpi-tests-")

    def tearDown(self) -> None:
        for server in self._servers:
            server.shutdown()
            server.server_close()
        self._tmpdir.cleanup()

    def _start_server(self, server: socketserver.TCPServer) -> socketserver.TCPServer:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self._threads.append(thread)
        self._servers.append(server)
        return server

    def _tls_server(self) -> TLSServer:
        certfile, keyfile = generate_localhost_cert(Path(self._tmpdir.name))
        return TLSServer(str(certfile), str(keyfile))

    def _maybe_ipv6_server(self) -> socketserver.TCPServer | None:
        if not socket.has_ipv6:
            return None
        try:
            return ThreadingTCPServerV6(("::1", 0), EchoHandler)
        except OSError:
            return None

    def test_socks5_echo(self) -> None:
        echo_server = self._start_server(ThreadingTCPServer(("127.0.0.1", 0), EchoHandler))
        with ProxyProcess(PROXY_BINARY) as proxy:
            with socks_connect(proxy.port, echo_server.server_address[1]) as sock:
                payload = b"plain socks payload"
                sock.sendall(payload)
                self.assertEqual(recv_exact(sock, len(payload)), payload)

    def test_socks4_echo(self) -> None:
        echo_server = self._start_server(ThreadingTCPServer(("127.0.0.1", 0), EchoHandler))
        with ProxyProcess(PROXY_BINARY) as proxy:
            with socks4_connect(proxy.port, echo_server.server_address[1]) as sock:
                payload = b"plain socks4 payload"
                sock.sendall(payload)
                self.assertEqual(recv_exact(sock, len(payload)), payload)

    def test_http_connect_echo(self) -> None:
        echo_server = self._start_server(ThreadingTCPServer(("127.0.0.1", 0), EchoHandler))
        with ProxyProcess(PROXY_BINARY, http_connect=True) as proxy:
            with http_connect(proxy.port, echo_server.server_address[1]) as sock:
                payload = b"http connect payload"
                sock.sendall(payload)
                self.assertEqual(recv_exact(sock, len(payload)), payload)

    def test_socks5_tls_tunnel(self) -> None:
        tls_server = self._start_server(self._tls_server())
        with ProxyProcess(PROXY_BINARY) as proxy:
            raw_sock = socks_connect(proxy.port, tls_server.server_address[1])
            context = ssl.create_default_context()
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
            with context.wrap_socket(raw_sock, server_hostname="localhost") as tls_sock:
                tls_sock.sendall(
                    b"GET / HTTP/1.1\r\n"
                    b"Host: localhost\r\n"
                    b"Connection: close\r\n"
                    b"\r\n"
                )
                response = recv_until(tls_sock, b"proxy tls ok")
                self.assertIn(b"HTTP/1.1 200 OK", response)
                self.assertTrue(response.endswith(b"proxy tls ok"))

    def test_socks5_udp_associate_echo(self) -> None:
        udp_server = self._start_server(ThreadingUDPServer(("127.0.0.1", 0), UDPEchoHandler))
        with ProxyProcess(PROXY_BINARY) as proxy:
            control_sock, relay = socks_udp_associate(proxy.port)
            with control_sock:
                payload = b"udp proxy payload"
                self.assertEqual(
                    udp_proxy_roundtrip(relay, udp_server.server_address[1], payload),
                    payload,
                )

    def test_udp_fake_burst_reaches_server_before_payload(self) -> None:
        udp_server = self._start_server(RecordingUDPServer(("127.0.0.1", 0), UDPEchoHandler))
        with ProxyProcess(PROXY_BINARY, extra_args=["--udp-fake", "2"]) as proxy:
            control_sock, relay = socks_udp_associate(proxy.port)
            with control_sock:
                payload = b"udp payload after fakes"
                echoed = udp_proxy_roundtrip(relay, udp_server.server_address[1], payload)
                self.assertEqual(echoed, payload)

        self.assertGreaterEqual(len(udp_server.packets), 3)
        self.assertEqual(udp_server.packets[-1], payload)
        self.assertEqual(udp_server.packets[0], b"\x00" * 64)
        self.assertEqual(udp_server.packets[1], b"\x00" * 64)

    def test_connection_churn_echo(self) -> None:
        echo_server = self._start_server(ThreadingTCPServer(("127.0.0.1", 0), EchoHandler))
        with ProxyProcess(PROXY_BINARY) as proxy:
            for idx in range(25):
                with socks_connect(proxy.port, echo_server.server_address[1]) as sock:
                    payload = f"burst-{idx}".encode("ascii")
                    sock.sendall(payload)
                    self.assertEqual(recv_exact(sock, len(payload)), payload)

    def test_no_domain_rejects_domain_requests(self) -> None:
        with ProxyProcess(PROXY_BINARY, extra_args=["-N"]) as proxy:
            sock, reply = socks_connect_domain(proxy.port, "localhost", 80)
            with sock:
                self.assertNotEqual(parse_socks5_reply(reply)[0], 0)

    def test_no_udp_rejects_udp_associate(self) -> None:
        with ProxyProcess(PROXY_BINARY, extra_args=["-U"]) as proxy:
            sock = socks_auth(proxy.port)
            with sock:
                request = b"\x05\x03\x00\x01" + socket.inet_aton("0.0.0.0") + b"\x00\x00"
                sock.sendall(request)
                reply = recv_socks5_reply(sock)
                self.assertNotEqual(parse_socks5_reply(reply)[0], 0)

    def test_connect_failure_does_not_yield_a_working_tunnel(self) -> None:
        closed_port = free_port()
        with ProxyProcess(PROXY_BINARY) as proxy:
            sock = socks_auth(proxy.port)
            with sock:
                request = b"\x05\x01\x00\x01" + socket.inet_aton("127.0.0.1") + closed_port.to_bytes(2, "big")
                sock.sendall(request)
                reply = recv_socks5_reply(sock)
                if parse_socks5_reply(reply)[0] != 0:
                    return
                sock.settimeout(1)
                try:
                    sock.sendall(b"closed-port-probe")
                    data = sock.recv(1024)
                except (BrokenPipeError, OSError, TimeoutError):
                    return
                self.assertEqual(data, b"")

    def test_external_socks_chain(self) -> None:
        echo_server = self._start_server(ThreadingTCPServer(("127.0.0.1", 0), EchoHandler))
        with ProxyProcess(PROXY_BINARY) as upstream:
            with ProxyProcess(PROXY_BINARY, extra_args=["-C", f"127.0.0.1:{upstream.port}"]) as proxy:
                with socks_connect(proxy.port, echo_server.server_address[1]) as sock:
                    payload = b"chained socks payload"
                    sock.sendall(payload)
                    self.assertEqual(recv_exact(sock, len(payload)), payload)

    def test_socks5_ipv6_echo(self) -> None:
        server = self._maybe_ipv6_server()
        if server is None:
            self.skipTest("IPv6 loopback is unavailable")
        echo_server = self._start_server(server)
        with ProxyProcess(PROXY_BINARY) as proxy:
            with socks_connect_ipv6(proxy.port, "::1", echo_server.server_address[1]) as sock:
                payload = b"ipv6 socks payload"
                sock.sendall(payload)
                self.assertEqual(recv_exact(sock, len(payload)), payload)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    args = parser.parse_args()

    global PROXY_BINARY
    PROXY_BINARY = args.binary

    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ProxyIntegrationTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
