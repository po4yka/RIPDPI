#!/usr/bin/env python3

from __future__ import annotations

import argparse
import os
import socket
import socketserver
import subprocess
import tempfile
import threading
import time
import unittest
from pathlib import Path

from test_proxy_integration import (
    EchoHandler,
    ThreadingTCPServer,
    free_port,
    parse_socks5_reply,
    recv_exact,
    recv_socks5_reply,
    socks_auth,
    socks_connect,
)


PROXY_BINARY = ""


class ManagedProxyProcess:
    def __init__(
        self,
        binary: str,
        *,
        extra_args: list[str] | None = None,
        env_updates: dict[str, str] | None = None,
        use_default_listen_args: bool = True,
        port: int | None = None,
    ):
        self.binary = binary
        self.extra_args = extra_args or []
        self.env_updates = env_updates or {}
        self.use_default_listen_args = use_default_listen_args
        self.port = port or free_port()
        self._log = tempfile.NamedTemporaryFile(prefix="ciadpi-", suffix=".log", delete=False)
        self._proc: subprocess.Popen[str] | None = None

    @property
    def pid(self) -> int:
        if not self._proc:
            raise AssertionError("process not started")
        return self._proc.pid

    def start(self) -> None:
        args = [self.binary]
        if self.use_default_listen_args:
            args.extend(["-i", "127.0.0.1", "-p", str(self.port), "-I", "0.0.0.0"])
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
                if self.use_default_listen_args:
                    with socks_auth(self.port):
                        pass
                else:
                    with socket.create_connection(("127.0.0.1", self.port), timeout=0.2):
                        pass
                return
            except (AssertionError, OSError):
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

    def read_log(self) -> str:
        self._log.flush()
        path = Path(self._log.name)
        return path.read_text() if path.exists() else ""

    def cleanup(self) -> None:
        self._log.close()
        Path(self._log.name).unlink(missing_ok=True)

    def __enter__(self) -> "ManagedProxyProcess":
        self.start()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.stop()
        self.cleanup()


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


class RecordingUpstreamSocksServer(ThreadingTCPServer):
    def __init__(self, server_address, handler_class):
        self.attempts = 0
        self._lock = threading.Lock()
        super().__init__(server_address, handler_class)

    def bump(self) -> None:
        with self._lock:
            self.attempts += 1


def _pipe(src: socket.socket, dst: socket.socket) -> None:
    try:
        while True:
            data = src.recv(4096)
            if not data:
                return
            dst.sendall(data)
    except OSError:
        return
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


class RecordingUpstreamSocksHandler(socketserver.BaseRequestHandler):
    def handle(self) -> None:
        self.server.bump()
        header = recv_exact(self.request, 2)
        if header[:1] != b"\x05":
            raise AssertionError(f"unexpected upstream socks version: {header!r}")
        recv_exact(self.request, header[1])
        self.request.sendall(b"\x05\x00")

        request = recv_exact(self.request, 4)
        atyp = request[3]
        if atyp == 0x01:
            raw = recv_exact(self.request, 6)
            host = socket.inet_ntoa(raw[:4])
            port = int.from_bytes(raw[4:6], "big")
        elif atyp == 0x04:
            raw = recv_exact(self.request, 18)
            host = socket.inet_ntop(socket.AF_INET6, raw[:16])
            port = int.from_bytes(raw[16:18], "big")
        else:
            raise AssertionError(f"unsupported upstream socks atyp: {atyp}")

        upstream = socket.create_connection((host, port), timeout=5)
        try:
            self.request.sendall(b"\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00")
            thread = threading.Thread(target=_pipe, args=(upstream, self.request), daemon=True)
            thread.start()
            _pipe(self.request, upstream)
            thread.join(timeout=1)
        finally:
            upstream.close()


class RustRuntimeMigrationTests(unittest.TestCase):
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

    def test_pidfile_is_created_and_removed(self) -> None:
        with tempfile.TemporaryDirectory(prefix="ciadpi-pidfile-") as tmpdir:
            pidfile = Path(tmpdir) / "ciadpi.pid"
            proxy = ManagedProxyProcess(PROXY_BINARY, extra_args=["--pidfile", str(pidfile)])
            proxy.start()
            try:
                deadline = time.time() + 5
                while time.time() < deadline and not pidfile.exists():
                    time.sleep(0.05)
                self.assertTrue(pidfile.exists(), proxy.read_log())
                self.assertEqual(pidfile.read_text().strip(), str(proxy.pid))
            finally:
                proxy.stop()
                self.assertFalse(pidfile.exists(), proxy.read_log())
                proxy.cleanup()

    def test_cache_file_stdout_dumps_entries_on_shutdown(self) -> None:
        echo = self._start_server(ThreadingTCPServer(("127.0.0.1", 0), EchoHandler))
        failing = self._start_server(FailingSocksServer(("127.0.0.1", 0), FailingSocksHandler))
        proxy = ManagedProxyProcess(
            PROXY_BINARY,
            extra_args=[
                "--to-socks5",
                f"127.0.0.1:{failing.server_address[1]}",
                "--auto",
                "conn",
                "--cache-file",
                "-",
                "--cache-ttl",
                "60",
            ],
        )
        proxy.start()
        try:
            with socks_connect(proxy.port, echo.server_address[1]) as sock:
                sock.sendall(b"cache")
                self.assertEqual(sock.recv(5), b"cache")
            self.assertEqual(failing.attempts, 1)
        finally:
            proxy.stop()
            log = proxy.read_log()
            proxy.cleanup()

        self.assertIn(f" 127.0.0.1 32 {echo.server_address[1]} ", log)

    def test_delay_conn_waits_for_first_payload_before_upstream_connect(self) -> None:
        echo = self._start_server(ThreadingTCPServer(("127.0.0.1", 0), EchoHandler))
        upstream = self._start_server(
            RecordingUpstreamSocksServer(("127.0.0.1", 0), RecordingUpstreamSocksHandler)
        )
        proxy = ManagedProxyProcess(
            PROXY_BINARY,
            extra_args=[
                "--hosts",
                ":delayed.example.test",
                "--to-socks5",
                f"127.0.0.1:{upstream.server_address[1]}",
            ],
        )
        proxy.start()
        try:
            sock = socks_connect(proxy.port, echo.server_address[1])
            try:
                self.assertEqual(upstream.attempts, 0)
                payload = b"GET / HTTP/1.1\r\nHost: delayed.example.test\r\n\r\n"
                sock.sendall(payload)
                self.assertEqual(sock.recv(len(payload)), payload)
                deadline = time.time() + 5
                while time.time() < deadline and upstream.attempts == 0:
                    time.sleep(0.05)
                self.assertEqual(upstream.attempts, 1)
            finally:
                sock.close()
        finally:
            proxy.stop()
            proxy.cleanup()

    def test_max_conn_limits_concurrent_clients(self) -> None:
        echo = self._start_server(ThreadingTCPServer(("127.0.0.1", 0), EchoHandler))
        proxy = ManagedProxyProcess(PROXY_BINARY, extra_args=["--max-conn", "1"])
        proxy.start()
        try:
            deadline = time.time() + 2
            first = None
            while time.time() < deadline and first is None:
                try:
                    first = socks_connect(proxy.port, echo.server_address[1])
                except OSError:
                    time.sleep(0.05)
            if first is None:
                raise AssertionError(proxy.read_log())
            try:
                second = socket.create_connection(("127.0.0.1", proxy.port), timeout=5)
                second.settimeout(2)
                try:
                    second.sendall(b"\x05\x01\x00")
                    reply = second.recv(2)
                except OSError:
                    reply = b""
                finally:
                    second.close()
                self.assertNotEqual(reply, b"\x05\x00", proxy.read_log())
            finally:
                first.close()
        finally:
            proxy.stop()
            proxy.cleanup()

    def test_shadowsocks_env_mode_tunnels_initial_payload(self) -> None:
        echo = self._start_server(ThreadingTCPServer(("127.0.0.1", 0), EchoHandler))
        port = free_port()
        proxy = ManagedProxyProcess(
            PROXY_BINARY,
            use_default_listen_args=False,
            port=port,
            env_updates={"SS_LOCAL_PORT": str(port)},
        )
        proxy.start()
        try:
            with socket.create_connection(("127.0.0.1", proxy.port), timeout=5) as sock:
                payload = b"shadow"
                request = (
                    b"\x01"
                    + socket.inet_aton("127.0.0.1")
                    + echo.server_address[1].to_bytes(2, "big")
                    + payload
                )
                sock.sendall(request)
                self.assertEqual(sock.recv(len(payload)), payload)
        finally:
            proxy.stop()
            proxy.cleanup()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    args = parser.parse_args()

    global PROXY_BINARY
    PROXY_BINARY = args.binary

    suite = unittest.defaultTestLoader.loadTestsFromTestCase(RustRuntimeMigrationTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
