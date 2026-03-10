#!/usr/bin/env python3

from __future__ import annotations

import argparse
import socket
import socketserver
import subprocess
import threading
import time
import unittest
from pathlib import Path

from test_proxy_integration import (
    EchoHandler,
    ProxyProcess,
    ThreadingTCPServer,
    recv_exact,
    recv_until,
    socks_connect,
)


PROXY_BINARY = ""


class FailingSocksServer(ThreadingTCPServer):
    def __init__(self, server_address, handler_class):
        self.attempts = 0
        self._lock = threading.Lock()
        super().__init__(server_address, handler_class)

    def bump(self) -> None:
        with self._lock:
            self.attempts += 1


class FailingSocksHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        self.server.bump()


class RecordingHTTPServer(ThreadingTCPServer):
    def __init__(self, server_address, handler_class, expected: bytes):
        self.expected = expected
        self.requests: list[bytes] = []
        self._lock = threading.Lock()
        super().__init__(server_address, handler_class)

    def record(self, request: bytes) -> None:
        with self._lock:
            self.requests.append(request)


class RedirectOnMutationHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        request = recv_until(self.request, b"\r\n\r\n")
        self.server.record(request)
        if request == self.server.expected:
            self.request.sendall(
                b"HTTP/1.1 200 OK\r\n"
                b"Content-Length: 2\r\n"
                b"Connection: close\r\n"
                b"\r\n"
                b"ok"
            )
        else:
            self.request.sendall(
                b"HTTP/1.1 302 Found\r\n"
                b"Location: http://blocked.example/\r\n"
                b"Content-Length: 0\r\n"
                b"Connection: close\r\n"
                b"\r\n"
            )


class RecordingRawServer(ThreadingTCPServer):
    def __init__(self, server_address, handler_class, expected: bytes, success: bytes):
        self.expected = expected
        self.success = success
        self.requests: list[bytes] = []
        self._lock = threading.Lock()
        super().__init__(server_address, handler_class)

    def record(self, request: bytes) -> None:
        with self._lock:
            self.requests.append(request)


class TlsErrFallbackHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        request = self.request.recv(4096)
        self.server.record(request)
        if request == self.server.expected:
            self.request.sendall(self.server.success)
        else:
            self.request.sendall(b"NOTTLS")


class TorstServer(ThreadingTCPServer):
    def __init__(self, server_address, handler_class, success: bytes):
        self.success = success
        self.requests: list[bytes] = []
        self._lock = threading.Lock()
        super().__init__(server_address, handler_class)

    def record(self, request: bytes) -> int:
        with self._lock:
            self.requests.append(request)
            return len(self.requests)


class TorstHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        request = self.request.recv(4096)
        attempt = self.server.record(request)
        if attempt > 1:
            self.request.sendall(self.server.success)
        else:
            self.request.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, b"\x01\x00\x00\x00\x00\x00\x00\x00")


class PartialTlsServer(ThreadingTCPServer):
    def __init__(
        self,
        server_address,
        handler_class,
        *,
        success: bytes,
        split_at: int,
        delay: float,
        retry_after_timeout: bool,
    ):
        self.success = success
        self.split_at = split_at
        self.delay = delay
        self.retry_after_timeout = retry_after_timeout
        self.requests: list[bytes] = []
        self._lock = threading.Lock()
        super().__init__(server_address, handler_class)

    def record(self, request: bytes) -> int:
        with self._lock:
            self.requests.append(request)
            return len(self.requests)


class PartialTlsHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        request = self.request.recv(4096)
        attempt = self.server.record(request)
        if self.server.retry_after_timeout and attempt > 1:
            self.request.sendall(self.server.success)
            return

        self.request.sendall(self.server.success[: self.server.split_at])
        time.sleep(self.server.delay)
        if not self.server.retry_after_timeout:
            self.request.sendall(self.server.success[self.server.split_at :])


class AutoRuntimeTests(unittest.TestCase):
    def setUp(self) -> None:
        self._servers: list[socketserver.BaseServer] = []
        self._threads: list[threading.Thread] = []

    def tearDown(self) -> None:
        for server in self._servers:
            server.shutdown()
            server.server_close()

    def _start_server(self, server: socketserver.BaseServer) -> socketserver.BaseServer:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self._servers.append(server)
        self._threads.append(thread)
        return server

    def _require_timeout_support(self) -> None:
        help_text = subprocess.run(
            [PROXY_BINARY, "--help"],
            capture_output=True,
            text=True,
            check=False,
        ).stdout
        self.assertIn("-T, --timeout", help_text)

    def test_connect_trigger_reuses_cached_fallback_route_within_process(self) -> None:
        echo = self._start_server(ThreadingTCPServer(("127.0.0.1", 0), EchoHandler))
        failing = self._start_server(FailingSocksServer(("127.0.0.1", 0), FailingSocksHandler))
        args = [
            "--to-socks5",
            f"127.0.0.1:{failing.server_address[1]}",
            "--auto",
            "conn",
            "--cache-ttl",
            "60",
        ]

        with ProxyProcess(PROXY_BINARY, extra_args=args) as proxy:
            with socks_connect(proxy.port, echo.server_address[1]) as sock:
                sock.sendall(b"first")
                self.assertEqual(sock.recv(5), b"first")
            self.assertEqual(failing.attempts, 1)

            with socks_connect(proxy.port, echo.server_address[1]) as sock:
                sock.sendall(b"second")
                self.assertEqual(sock.recv(6), b"second")
            self.assertEqual(
                failing.attempts,
                1,
                "cached fallback should avoid a second upstream SOCKS attempt",
            )

    def test_redirect_trigger_replays_first_request_with_next_group(self) -> None:
        payload = b"GET / HTTP/1.1\r\nHost: www.wikipedia.org\r\n\r\n"
        server = self._start_server(
            RecordingHTTPServer(("127.0.0.1", 0), RedirectOnMutationHandler, payload)
        )

        with ProxyProcess(PROXY_BINARY, extra_args=["--mod-http", "rh", "--auto", "redirect"]) as proxy:
            with socks_connect(proxy.port, server.server_address[1]) as sock:
                sock.sendall(payload)
                response = recv_until(sock, b"\r\n\r\n")
                if b"Content-Length: 2" in response:
                    response += sock.recv(2)

        self.assertIn(b"HTTP/1.1 200 OK", response)
        self.assertEqual(len(server.requests), 2)
        self.assertNotEqual(server.requests[0], payload)
        self.assertEqual(server.requests[1], payload)

    def test_ssl_err_trigger_replays_original_client_hello(self) -> None:
        payload = (Path(__file__).resolve().parent / "corpus" / "packets" / "tls_client_hello.bin").read_bytes()
        success = b"\x16\x03\x03\x00\x01\x02"
        server = self._start_server(
            RecordingRawServer(("127.0.0.1", 0), TlsErrFallbackHandler, payload, success)
        )

        with ProxyProcess(PROXY_BINARY, extra_args=["--tlsminor", "5", "--auto", "ssl_err"]) as proxy:
            with socks_connect(proxy.port, server.server_address[1]) as sock:
                sock.sendall(payload)
                response = sock.recv(len(success))

        self.assertEqual(response, success)
        self.assertEqual(len(server.requests), 2)
        self.assertNotEqual(server.requests[0], payload)
        self.assertEqual(server.requests[1], payload)

    def test_torst_trigger_reconnects_after_first_response_reset(self) -> None:
        payload = b"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n"
        server = self._start_server(TorstServer(("127.0.0.1", 0), TorstHandler, b"ok"))

        with ProxyProcess(
            PROXY_BINARY,
            extra_args=["--split", "8", "--auto", "torst"],
        ) as proxy:
            with socks_connect(proxy.port, server.server_address[1]) as sock:
                sock.sendall(payload)
                self.assertEqual(sock.recv(2), b"ok")

        self.assertEqual(len(server.requests), 2)

    def test_partial_tls_timeout_count_limit_triggers_torst_retry(self) -> None:
        self._require_timeout_support()
        payload = (Path(__file__).resolve().parent / "corpus" / "packets" / "tls_client_hello.bin").read_bytes()
        success = b"\x16\x03\x03\x00\x01\x02"
        server = self._start_server(
            PartialTlsServer(
                ("127.0.0.1", 0),
                PartialTlsHandler,
                success=success,
                split_at=3,
                delay=0.18,
                retry_after_timeout=True,
            )
        )

        with ProxyProcess(
            PROXY_BINARY,
            extra_args=["--auto", "torst", "-T", "3:0.05:2:64"],
        ) as proxy:
            with socks_connect(proxy.port, server.server_address[1]) as sock:
                sock.sendall(payload)
                response = recv_exact(sock, len(success))

        self.assertEqual(response, success)
        self.assertEqual(len(server.requests), 2)

    def test_timeout_bytes_limit_disables_partial_torst_retry(self) -> None:
        self._require_timeout_support()
        payload = (Path(__file__).resolve().parent / "corpus" / "packets" / "tls_client_hello.bin").read_bytes()
        success = b"\x16\x03\x03\x00\x01\x02"
        server = self._start_server(
            PartialTlsServer(
                ("127.0.0.1", 0),
                PartialTlsHandler,
                success=success,
                split_at=5,
                delay=0.12,
                retry_after_timeout=False,
            )
        )

        with ProxyProcess(
            PROXY_BINARY,
            extra_args=["--auto", "torst", "-T", "3:0.05:2:4"],
        ) as proxy:
            with socks_connect(proxy.port, server.server_address[1]) as sock:
                sock.sendall(payload)
                response = recv_exact(sock, len(success))

        self.assertEqual(response, success)
        self.assertEqual(
            len(server.requests),
            1,
            "timeout byte limit should disable torst retry after enough response bytes arrive",
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    args = parser.parse_args()

    global PROXY_BINARY
    PROXY_BINARY = args.binary

    suite = unittest.defaultTestLoader.loadTestsFromTestCase(AutoRuntimeTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
