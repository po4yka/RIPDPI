#!/usr/bin/env python3

from __future__ import annotations

import argparse
import array
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

from test_proxy_integration import (
    EchoHandler,
    ProxyProcess,
    ThreadingTCPServer,
    recv_exact,
    socks_connect,
)


PROXY_BINARY = ""
PROXY_PORT = 18080
TARGET_PORT = 18081
PAYLOAD = b"transparent payload"

SERVER_SCRIPT = """
import pathlib
import socket
import sys

host = sys.argv[1]
port = int(sys.argv[2])
ready = pathlib.Path(sys.argv[3])
output = pathlib.Path(sys.argv[4])
expected = int(sys.argv[5])

sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind((host, port))
sock.listen(1)
ready.write_text("ready")
conn, _ = sock.accept()
conn.settimeout(10)
data = bytearray()
while len(data) < expected:
    chunk = conn.recv(4096)
    if not chunk:
        break
    data.extend(chunk)
output.write_bytes(bytes(data))
if len(data) == expected:
    conn.sendall(bytes(data))
conn.close()
sock.close()
"""

CLIENT_SCRIPT = """
import pathlib
import socket
import sys

host = sys.argv[1]
port = int(sys.argv[2])
payload = bytes.fromhex(sys.argv[3])
output = pathlib.Path(sys.argv[4])

sock = socket.create_connection((host, port), timeout=10)
sock.settimeout(10)
sock.sendall(payload)
sock.shutdown(socket.SHUT_WR)
echoed = bytearray()
while len(echoed) < len(payload):
    chunk = sock.recv(4096)
    if not chunk:
        break
    echoed.extend(chunk)
output.write_bytes(bytes(echoed))
sock.close()
"""

WAIT_PORT_SCRIPT = """
import socket
import sys
import time

host = sys.argv[1]
port = int(sys.argv[2])
deadline = time.time() + 5
while time.time() < deadline:
    try:
        with socket.create_connection((host, port), timeout=0.2):
            raise SystemExit(0)
    except OSError:
        time.sleep(0.05)
raise SystemExit(1)
"""


class ProtectServer:
    def __init__(self, path: Path):
        self.path = path
        self.received_peer: tuple[str, int] | None = None
        self.received_local: tuple[str, int] | None = None
        self.error: str | None = None
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()

    def start(self) -> None:
        self._thread = threading.Thread(target=self._serve, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=2)

    def _serve(self) -> None:
        try:
            self.path.unlink(missing_ok=True)
            server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            server.bind(str(self.path))
            server.listen(1)
            server.settimeout(5)
            conn, _ = server.accept()
            try:
                msg, ancdata, _flags, _addr = conn.recvmsg(1, socket.CMSG_SPACE(array.array("i").itemsize))
                if msg != b"1":
                    raise AssertionError(f"unexpected protect payload: {msg!r}")
                received_fd = None
                for level, msg_type, data in ancdata:
                    if level == socket.SOL_SOCKET and msg_type == socket.SCM_RIGHTS:
                        fds = array.array("i")
                        fds.frombytes(data[: len(data) - (len(data) % fds.itemsize)])
                        if fds:
                            received_fd = fds[0]
                            break
                if received_fd is None:
                    raise AssertionError("protect helper did not receive a socket fd")
                dup_sock = socket.fromfd(received_fd, socket.AF_INET, socket.SOCK_STREAM)
                try:
                    conn.sendall(b"1")
                    deadline = time.time() + 5
                    last_error = None
                    while time.time() < deadline:
                        try:
                            local = dup_sock.getsockname()
                            if local[1] != 0:
                                self.received_local = local
                        except OSError:
                            pass
                        try:
                            self.received_peer = dup_sock.getpeername()
                            local = dup_sock.getsockname()
                            if local[1] != 0:
                                self.received_local = local
                            break
                        except OSError as exc:
                            last_error = str(exc)
                            time.sleep(0.05)
                    if self.received_peer is None and self.received_local is None:
                        raise AssertionError(
                            f"protect helper did not observe socket state: {last_error}"
                        )
                finally:
                    dup_sock.close()
                    os.close(received_fd)
            finally:
                conn.close()
                server.close()
        except Exception as exc:  # noqa: BLE001
            self.error = str(exc)


class ManagedProcess:
    def __init__(self, args: list[str]):
        self.args = args
        self._log = tempfile.NamedTemporaryFile(prefix="ciadpi-linux-feature-", suffix=".log", delete=False)
        self._proc: subprocess.Popen[str] | None = None

    def start(self) -> None:
        self._proc = subprocess.Popen(
            self.args,
            stdout=self._log,
            stderr=subprocess.STDOUT,
            text=True,
        )

    def stop(self) -> None:
        if self._proc and self._proc.poll() is None:
            self._proc.terminate()
            try:
                self._proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._proc.kill()
                self._proc.wait(timeout=5)
        self._log.close()

    def read_log(self) -> str:
        self._log.flush()
        path = Path(self._log.name)
        return path.read_text() if path.exists() else ""

    def cleanup(self) -> None:
        Path(self._log.name).unlink(missing_ok=True)


def find_system_tool(name: str) -> str | None:
    path = shutil.which(name)
    if path is not None:
        return path
    for candidate in (Path("/usr/sbin") / name, Path("/sbin") / name):
        if candidate.exists():
            return str(candidate)
    return None


class TransparentLab:
    counter = 0

    @staticmethod
    def supported() -> bool:
        if sys.platform != "linux":
            return False
        if find_system_tool("ip") is None:
            return False
        if not (find_system_tool("iptables") or find_system_tool("nft")):
            return False
        if os.geteuid() == 0:
            return True
        if not shutil.which("sudo"):
            return False
        return subprocess.run(["sudo", "-n", "true"], capture_output=True, check=False).returncode == 0

    def __init__(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory(prefix="ciadpi-transparent-")
        self.tmpdir = Path(self._tmpdir.name)
        tag = f"{(os.getpid() + TransparentLab.counter) & 0xFFFF:04x}"
        TransparentLab.counter += 1
        self.client_ns = f"ciadpi-client-{tag}"
        self.proxy_ns = f"ciadpi-proxy-{tag}"
        self.server_ns = f"ciadpi-server-{tag}"
        self.namespaces = [self.client_ns, self.proxy_ns, self.server_ns]
        self.links = {
            "client": f"ct{tag}",
            "proxy_left": f"pl{tag}",
            "proxy_right": f"pr{tag}",
            "server": f"sv{tag}",
        }

    def sudo(self, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
        return subprocess.run(["sudo", "-n", *args], check=check, capture_output=True, text=True)

    def exec_run(self, namespace: str, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["sudo", "-n", "ip", "netns", "exec", namespace, *args],
            check=check,
            capture_output=True,
            text=True,
        )

    def exec_popen(self, namespace: str, *args: str) -> ManagedProcess:
        proc = ManagedProcess(["sudo", "-n", "ip", "netns", "exec", namespace, *args])
        proc.start()
        return proc

    def setup(self) -> None:
        ip = find_system_tool("ip")
        assert ip is not None
        iptables = find_system_tool("iptables")
        nft = find_system_tool("nft")
        self.delete_namespaces()
        for namespace in self.namespaces:
            self.sudo(ip, "netns", "add", namespace)
            self.sudo(ip, "-n", namespace, "link", "set", "lo", "up")

        self.sudo(ip, "link", "add", self.links["client"], "type", "veth", "peer", "name", self.links["proxy_left"])
        self.sudo(ip, "link", "set", self.links["client"], "netns", self.client_ns)
        self.sudo(ip, "link", "set", self.links["proxy_left"], "netns", self.proxy_ns)

        self.sudo(ip, "link", "add", self.links["proxy_right"], "type", "veth", "peer", "name", self.links["server"])
        self.sudo(ip, "link", "set", self.links["proxy_right"], "netns", self.proxy_ns)
        self.sudo(ip, "link", "set", self.links["server"], "netns", self.server_ns)

        self.sudo(ip, "-n", self.client_ns, "addr", "add", "10.220.1.2/24", "dev", self.links["client"])
        self.sudo(ip, "-n", self.client_ns, "link", "set", self.links["client"], "up")
        self.sudo(ip, "-n", self.client_ns, "route", "add", "default", "via", "10.220.1.1")

        self.sudo(ip, "-n", self.proxy_ns, "addr", "add", "10.220.1.1/24", "dev", self.links["proxy_left"])
        self.sudo(ip, "-n", self.proxy_ns, "addr", "add", "10.220.2.1/24", "dev", self.links["proxy_right"])
        self.sudo(ip, "-n", self.proxy_ns, "link", "set", self.links["proxy_left"], "up")
        self.sudo(ip, "-n", self.proxy_ns, "link", "set", self.links["proxy_right"], "up")

        self.sudo(ip, "-n", self.server_ns, "addr", "add", "10.220.2.2/24", "dev", self.links["server"])
        self.sudo(ip, "-n", self.server_ns, "link", "set", self.links["server"], "up")
        self.sudo(ip, "-n", self.server_ns, "route", "add", "default", "via", "10.220.2.1")

        if iptables is not None:
            self.exec_run(
                self.proxy_ns,
                iptables,
                "-t",
                "nat",
                "-A",
                "PREROUTING",
                "-p",
                "tcp",
                "-s",
                "10.220.1.2",
                "-d",
                "10.220.2.2",
                "--dport",
                str(TARGET_PORT),
                "-j",
                "REDIRECT",
                "--to-ports",
                str(PROXY_PORT),
            )
            return

        assert nft is not None
        self.exec_run(self.proxy_ns, nft, "add", "table", "ip", "nat")
        self.exec_run(
            self.proxy_ns,
            nft,
            "add",
            "chain",
            "ip",
            "nat",
            "prerouting",
            "{",
            "type",
            "nat",
            "hook",
            "prerouting",
            "priority",
            "dstnat;",
            "}",
        )
        self.exec_run(
            self.proxy_ns,
            nft,
            "add",
            "rule",
            "ip",
            "nat",
            "prerouting",
            "ip",
            "saddr",
            "10.220.1.2",
            "ip",
            "daddr",
            "10.220.2.2",
            "tcp",
            "dport",
            str(TARGET_PORT),
            "redirect",
            "to",
            f":{PROXY_PORT}",
        )

    def delete_namespaces(self) -> None:
        ip = find_system_tool("ip")
        if ip is None:
            return
        for namespace in reversed(self.namespaces):
            self.sudo(ip, "netns", "del", namespace, check=False)

    def cleanup(self) -> None:
        self.delete_namespaces()
        self._tmpdir.cleanup()


@unittest.skipUnless(sys.platform == "linux", "protect_path runtime is Linux-only")
class LinuxProtectPathTests(unittest.TestCase):
    def setUp(self) -> None:
        self._servers: list[ThreadingTCPServer] = []
        self._threads: list[threading.Thread] = []
        self._tmpdir = tempfile.TemporaryDirectory(prefix="ciadpi-protect-")

    def tearDown(self) -> None:
        for server in self._servers:
            server.shutdown()
            server.server_close()
        self._tmpdir.cleanup()

    def _start_server(self, server: ThreadingTCPServer) -> ThreadingTCPServer:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self._servers.append(server)
        self._threads.append(thread)
        return server

    def test_protect_path_receives_outbound_socket_fd(self) -> None:
        class RecordingEchoHandler(EchoHandler):
            def handle(self) -> None:
                self.server.last_client_address = self.client_address
                super().handle()

        echo_server = self._start_server(ThreadingTCPServer(("127.0.0.1", 0), RecordingEchoHandler))
        path = Path(self._tmpdir.name) / "protect.sock"
        protect = ProtectServer(path)
        protect.start()
        try:
            with ProxyProcess(PROXY_BINARY, extra_args=["--protect-path", str(path)]) as proxy:
                with socks_connect(proxy.port, echo_server.server_address[1]) as sock:
                    payload = b"protect path payload"
                    sock.sendall(payload)
                    self.assertEqual(recv_exact(sock, len(payload)), payload)
            deadline = time.time() + 2
            while time.time() < deadline and protect.received_peer is None and protect.error is None:
                time.sleep(0.05)
        finally:
            protect.stop()

        self.assertIsNone(protect.error, protect.error)
        self.assertIsNotNone(protect.received_local)
        self.assertEqual(protect.received_local[1], echo_server.last_client_address[1])
        if protect.received_peer is not None:
            self.assertEqual(protect.received_peer[1], echo_server.server_address[1])


@unittest.skipUnless(
    TransparentLab.supported(),
    "transparent proxy test requires Linux netns plus an available NAT redirect backend",
)
class LinuxTransparentProxyTests(unittest.TestCase):
    def setUp(self) -> None:
        self.lab = TransparentLab()
        self.lab.setup()
        self._processes: list[ManagedProcess] = []

    def tearDown(self) -> None:
        for proc in reversed(self._processes):
            proc.stop()
            proc.cleanup()
        self.lab.cleanup()

    def _start_server(self) -> tuple[ManagedProcess, Path]:
        ready = self.lab.tmpdir / "server.ready"
        output = self.lab.tmpdir / "server.out"
        proc = self.lab.exec_popen(
            self.lab.server_ns,
            "python3",
            "-c",
            SERVER_SCRIPT,
            "10.220.2.2",
            str(TARGET_PORT),
            str(ready),
            str(output),
            str(len(PAYLOAD)),
        )
        self._processes.append(proc)
        deadline = time.time() + 5
        while time.time() < deadline:
            if ready.exists():
                return proc, output
            if proc._proc and proc._proc.poll() is not None:
                raise AssertionError(f"server exited early:\n{proc.read_log()}")
            time.sleep(0.05)
        raise AssertionError(f"server did not start:\n{proc.read_log()}")

    def _start_proxy(self) -> ManagedProcess:
        proc = self.lab.exec_popen(
            self.lab.proxy_ns,
            str(Path(PROXY_BINARY).resolve()),
            "-E",
            "-i",
            "0.0.0.0",
            "-p",
            str(PROXY_PORT),
            "-I",
            "0.0.0.0",
        )
        self._processes.append(proc)
        deadline = time.time() + 5
        while time.time() < deadline:
            status = self.lab.exec_run(
                self.lab.proxy_ns,
                "python3",
                "-c",
                WAIT_PORT_SCRIPT,
                "10.220.1.1",
                str(PROXY_PORT),
                check=False,
            )
            if status.returncode == 0:
                return proc
            if proc._proc and proc._proc.poll() is not None:
                raise AssertionError(f"proxy exited early:\n{proc.read_log()}")
            time.sleep(0.05)
        raise AssertionError(f"proxy did not start:\n{proc.read_log()}")

    def test_transparent_redirect_relays_to_original_destination(self) -> None:
        server, output = self._start_server()
        proxy = self._start_proxy()
        echoed = self.lab.tmpdir / "client.out"
        client = self.lab.exec_run(
            self.lab.client_ns,
            "python3",
            "-c",
            CLIENT_SCRIPT,
            "10.220.2.2",
            str(TARGET_PORT),
            PAYLOAD.hex(),
            str(echoed),
            check=False,
        )
        if client.returncode != 0:
            raise AssertionError(
                f"client failed ({client.returncode})\nstdout:\n{client.stdout}\nstderr:\n{client.stderr}\nproxy:\n{proxy.read_log()}"
            )

        deadline = time.time() + 5
        while time.time() < deadline:
            if output.exists() and output.stat().st_size >= len(PAYLOAD):
                break
            if server._proc and server._proc.poll() is not None:
                break
            time.sleep(0.05)

        self.assertTrue(output.exists(), server.read_log())
        self.assertEqual(output.read_bytes(), PAYLOAD, server.read_log())
        self.assertEqual(echoed.read_bytes(), PAYLOAD, proxy.read_log())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    args = parser.parse_args()

    global PROXY_BINARY
    PROXY_BINARY = args.binary

    suite = unittest.TestSuite()
    for case in (LinuxProtectPathTests, LinuxTransparentProxyTests):
        suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(case))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
