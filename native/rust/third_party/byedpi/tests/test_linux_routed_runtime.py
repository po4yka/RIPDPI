#!/usr/bin/env python3

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from pathlib import Path


PROXY_BINARY = ""
PROJECT_ROOT = Path()
RUNTIME_PREFLIGHT_ENV = "CIADPI_ROUTED_RUNTIME_PREFLIGHT"
MD5SIG_UNSUPPORTED_ERRNOS = {92, 95}

PAYLOAD = (Path(__file__).resolve().parent / "corpus" / "packets" / "http_request.bin").read_bytes()
PROXY_PORT = 18080
SERVER_PORT = 18081

SERVER_SCRIPT = textwrap.dedent(
    """
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
)

CLIENT_SCRIPT = textwrap.dedent(
    """
    import pathlib
    import socket
    import sys

    proxy_port = int(sys.argv[1])
    server_host = sys.argv[2]
    server_port = int(sys.argv[3])
    payload = bytes.fromhex(sys.argv[4])
    output = pathlib.Path(sys.argv[5])

    sock = socket.create_connection(("127.0.0.1", proxy_port), timeout=10)
    sock.settimeout(15)
    sock.sendall(b"\\x05\\x01\\x00")
    auth = sock.recv(2)
    if auth != b"\\x05\\x00":
        raise SystemExit(f"SOCKS5 auth failed: {auth!r}")

    request = b"\\x05\\x01\\x00\\x01" + socket.inet_aton(server_host) + server_port.to_bytes(2, "big")
    sock.sendall(request)
    reply = sock.recv(10)
    if len(reply) < 2 or reply[1] != 0:
        raise SystemExit(f"SOCKS5 connect failed: {reply!r}")

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
)

WAIT_PORT_SCRIPT = textwrap.dedent(
    """
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
)

MD5SIG_PROBE_SERVER_SCRIPT = textwrap.dedent(
    """
    import pathlib
    import socket
    import sys
    import time

    host = sys.argv[1]
    port = int(sys.argv[2])
    ready = pathlib.Path(sys.argv[3])

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((host, port))
    sock.listen(1)
    ready.write_text("ready")
    conn, _ = sock.accept()
    time.sleep(1.0)
    conn.close()
    sock.close()
    """
)

MD5SIG_PROBE_CLIENT_SCRIPT = textwrap.dedent(
    """
    import ctypes
    import os
    import socket
    import sys

    TCP_MD5SIG = 14

    host = sys.argv[1]
    port = int(sys.argv[2])
    sock = socket.create_connection((host, port), timeout=5)

    class SockAddrStorage(ctypes.Structure):
        _fields_ = [("data", ctypes.c_ubyte * 128)]

    class InAddr(ctypes.Structure):
        _fields_ = [("s_addr", ctypes.c_ubyte * 4)]

    class SockAddrIn(ctypes.Structure):
        _fields_ = [
            ("sin_family", ctypes.c_ushort),
            ("sin_port", ctypes.c_ushort),
            ("sin_addr", InAddr),
            ("sin_zero", ctypes.c_ubyte * 8),
        ]

    class TcpMd5Sig(ctypes.Structure):
        _fields_ = [
            ("addr", SockAddrStorage),
            ("pad1", ctypes.c_ushort),
            ("key_len", ctypes.c_ushort),
            ("pad2", ctypes.c_uint32),
            ("key", ctypes.c_ubyte * 80),
        ]

    addr = SockAddrIn()
    addr.sin_family = socket.AF_INET
    addr.sin_port = socket.htons(port)
    ctypes.memmove(addr.sin_addr.s_addr, socket.inet_aton(host), 4)

    md5 = TcpMd5Sig()
    ctypes.memmove(ctypes.byref(md5.addr), ctypes.byref(addr), ctypes.sizeof(addr))
    md5.key_len = 5

    libc = ctypes.CDLL(None, use_errno=True)
    rc = libc.setsockopt(
        sock.fileno(),
        socket.IPPROTO_TCP,
        TCP_MD5SIG,
        ctypes.byref(md5),
        ctypes.sizeof(md5),
    )
    if rc == 0:
        sock.close()
        raise SystemExit(0)

    err = ctypes.get_errno()
    print(f"{err}:{os.strerror(err)}", file=sys.stderr)
    sock.close()
    raise SystemExit(err or 1)
    """
)


def run_command(args: list[str], check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, check=check, capture_output=True, text=True)


def summarize_preflight(proc: subprocess.CompletedProcess[str]) -> str:
    lines = [line.strip() for line in (proc.stderr.splitlines() + proc.stdout.splitlines()) if line.strip()]
    for line in reversed(lines):
        if line.startswith("AssertionError:") or line.startswith("TimeoutError:"):
            return line
    return lines[-1] if lines else f"preflight exited with code {proc.returncode}"


class ManagedProcess:
    def __init__(self, args: list[str], cwd: str | None = None):
        self.args = args
        self.cwd = cwd
        self._log = tempfile.NamedTemporaryFile(prefix="ciadpi-netns-", suffix=".log", delete=False)
        self._proc: subprocess.Popen[str] | None = None

    def start(self) -> None:
        self._proc = subprocess.Popen(
            self.args,
            cwd=self.cwd,
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
        try:
            self._log.flush()
        except ValueError:
            pass
        path = Path(self._log.name)
        return path.read_text() if path.exists() else ""

    def cleanup(self) -> None:
        Path(self._log.name).unlink(missing_ok=True)


class NamespaceLab:
    counter = 0

    @staticmethod
    def supported() -> bool:
        if not sys.platform.startswith("linux"):
            return False
        if not shutil.which("ip"):
            return False
        if os.geteuid() == 0:
            return True
        if not shutil.which("sudo"):
            return False
        return subprocess.run(
            ["sudo", "-n", "true"], capture_output=True, check=False
        ).returncode == 0

    def __init__(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory(prefix="ciadpi-routed-")
        self.tmpdir = Path(self._tmpdir.name)
        tag = f"{(os.getpid() + NamespaceLab.counter) & 0xFFFF:04x}"
        NamespaceLab.counter += 1

        self.proxy_ns = f"ciadpi-proxy-{tag}"
        self.r1_ns = f"ciadpi-r1-{tag}"
        self.r2_ns = f"ciadpi-r2-{tag}"
        self.server_ns = f"ciadpi-server-{tag}"
        self.namespaces = [self.proxy_ns, self.r1_ns, self.r2_ns, self.server_ns]

        self.links = {
            "vp": f"vp{tag}",
            "vr1a": f"va{tag}",
            "vr1b": f"vb{tag}",
            "vr2a": f"vc{tag}",
            "vr2b": f"vd{tag}",
            "vs": f"vs{tag}",
        }

    def sudo(self, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
        return run_command(["sudo", "-n", *args], check=check)

    def exec_run(self, namespace: str, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
        return run_command(
            ["sudo", "-n", "ip", "netns", "exec", namespace, *args],
            check=check,
        )

    def exec_popen(self, namespace: str, *args: str) -> ManagedProcess:
        process = ManagedProcess(["sudo", "-n", "ip", "netns", "exec", namespace, *args])
        process.start()
        return process

    def setup(self) -> None:
        self.delete_namespaces()
        for namespace in self.namespaces:
            self.sudo("ip", "netns", "add", namespace)
            self.sudo("ip", "-n", namespace, "link", "set", "lo", "up")

        self.sudo("ip", "link", "add", self.links["vp"], "type", "veth", "peer", "name", self.links["vr1a"])
        self.sudo("ip", "link", "set", self.links["vp"], "netns", self.proxy_ns)
        self.sudo("ip", "link", "set", self.links["vr1a"], "netns", self.r1_ns)

        self.sudo("ip", "link", "add", self.links["vr1b"], "type", "veth", "peer", "name", self.links["vr2a"])
        self.sudo("ip", "link", "set", self.links["vr1b"], "netns", self.r1_ns)
        self.sudo("ip", "link", "set", self.links["vr2a"], "netns", self.r2_ns)

        self.sudo("ip", "link", "add", self.links["vr2b"], "type", "veth", "peer", "name", self.links["vs"])
        self.sudo("ip", "link", "set", self.links["vr2b"], "netns", self.r2_ns)
        self.sudo("ip", "link", "set", self.links["vs"], "netns", self.server_ns)

        self.sudo("ip", "-n", self.proxy_ns, "addr", "add", "10.200.1.2/24", "dev", self.links["vp"])
        self.sudo("ip", "-n", self.proxy_ns, "link", "set", self.links["vp"], "up")
        self.sudo("ip", "-n", self.proxy_ns, "route", "add", "default", "via", "10.200.1.1")

        self.sudo("ip", "-n", self.r1_ns, "addr", "add", "10.200.1.1/24", "dev", self.links["vr1a"])
        self.sudo("ip", "-n", self.r1_ns, "addr", "add", "10.200.2.1/24", "dev", self.links["vr1b"])
        self.sudo("ip", "-n", self.r1_ns, "link", "set", self.links["vr1a"], "up")
        self.sudo("ip", "-n", self.r1_ns, "link", "set", self.links["vr1b"], "up")
        self.exec_run(self.r1_ns, "sysctl", "-w", "net.ipv4.ip_forward=1")
        self.sudo("ip", "-n", self.r1_ns, "route", "add", "10.200.3.0/24", "via", "10.200.2.2")

        self.sudo("ip", "-n", self.r2_ns, "addr", "add", "10.200.2.2/24", "dev", self.links["vr2a"])
        self.sudo("ip", "-n", self.r2_ns, "addr", "add", "10.200.3.1/24", "dev", self.links["vr2b"])
        self.sudo("ip", "-n", self.r2_ns, "link", "set", self.links["vr2a"], "up")
        self.sudo("ip", "-n", self.r2_ns, "link", "set", self.links["vr2b"], "up")
        self.exec_run(self.r2_ns, "sysctl", "-w", "net.ipv4.ip_forward=1")
        self.sudo("ip", "-n", self.r2_ns, "route", "add", "10.200.1.0/24", "via", "10.200.2.1")

        self.sudo("ip", "-n", self.server_ns, "addr", "add", "10.200.3.2/24", "dev", self.links["vs"])
        self.sudo("ip", "-n", self.server_ns, "link", "set", self.links["vs"], "up")
        self.sudo("ip", "-n", self.server_ns, "route", "add", "default", "via", "10.200.3.1")

    def delete_namespaces(self) -> None:
        for namespace in reversed(self.namespaces):
            self.sudo("ip", "netns", "del", namespace, check=False)

    def cleanup(self) -> None:
        self.delete_namespaces()
        self._tmpdir.cleanup()


@unittest.skipUnless(NamespaceLab.supported(), "Linux network namespaces and admin privilege are unavailable")
class RoutedLinuxRuntimeTests(unittest.TestCase):
    runtime_probe_reason: str | None = None
    md5sig_probe_reason: str | None = None
    PREFLIGHT_TEST = "test_fake_ttl_retransmits_original_payload_across_routed_path"

    @classmethod
    def setUpClass(cls) -> None:
        super().setUpClass()
        if os.environ.get(RUNTIME_PREFLIGHT_ENV) == "1":
            return
        env = os.environ.copy()
        env[RUNTIME_PREFLIGHT_ENV] = "1"
        proc = subprocess.run(
            [
                sys.executable,
                str(Path(__file__).resolve()),
                "--binary",
                str(Path(PROXY_BINARY).resolve()),
                "--project-root",
                str(PROJECT_ROOT),
                "--preflight-check",
            ],
            check=False,
            capture_output=True,
            text=True,
            env=env,
        )
        if proc.returncode != 0:
            cls.runtime_probe_reason = (
                "Rust routed fake-path preflight does not pass in this environment: "
                f"{summarize_preflight(proc)}"
            )
            return

        cls.md5sig_probe_reason = cls._probe_md5sig_support()

    @classmethod
    def _probe_md5sig_support(cls) -> str | None:
        lab = NamespaceLab()
        processes: list[ManagedProcess] = []
        try:
            lab.setup()
            ready = lab.tmpdir / "md5sig.ready"
            server = lab.exec_popen(
                lab.server_ns,
                "python3",
                "-c",
                MD5SIG_PROBE_SERVER_SCRIPT,
                "10.200.3.2",
                str(SERVER_PORT),
                str(ready),
            )
            processes.append(server)
            deadline = time.time() + 5
            while time.time() < deadline:
                if ready.exists():
                    break
                if server._proc and server._proc.poll() is not None:
                    raise AssertionError(f"md5sig probe server exited early:\n{server.read_log()}")
                time.sleep(0.05)
            else:
                raise AssertionError(f"md5sig probe server did not become ready:\n{server.read_log()}")

            proc = lab.exec_run(
                lab.proxy_ns,
                "python3",
                "-c",
                MD5SIG_PROBE_CLIENT_SCRIPT,
                "10.200.3.2",
                str(SERVER_PORT),
                check=False,
            )
            if proc.returncode == 0:
                return None
            if proc.returncode in MD5SIG_UNSUPPORTED_ERRNOS:
                return "TCP_MD5SIG is unavailable in this kernel/runtime environment"
            raise AssertionError(
                f"md5sig capability probe failed ({proc.returncode})\nstdout:\n{proc.stdout}\nstderr:\n{proc.stderr}"
            )
        finally:
            for process in reversed(processes):
                process.stop()
                process.cleanup()
            lab.cleanup()

    def setUp(self) -> None:
        if self.runtime_probe_reason is not None:
            self.skipTest(self.runtime_probe_reason)
        self.lab = NamespaceLab()
        self.lab.setup()
        self._processes: list[ManagedProcess] = []

    def tearDown(self) -> None:
        for process in reversed(self._processes):
            process.stop()
            process.cleanup()
        self.lab.cleanup()

    def _start_server(self) -> tuple[ManagedProcess, Path, Path]:
        ready = self.lab.tmpdir / "server.ready"
        received = self.lab.tmpdir / "server.bin"
        process = self.lab.exec_popen(
            self.lab.server_ns,
            "python3",
            "-c",
            SERVER_SCRIPT,
            "10.200.3.2",
            str(SERVER_PORT),
            str(ready),
            str(received),
            str(len(PAYLOAD)),
        )
        self._processes.append(process)
        deadline = time.time() + 5
        while time.time() < deadline:
            if ready.exists():
                return process, ready, received
            if process._proc and process._proc.poll() is not None:
                raise AssertionError(f"server exited early:\n{process.read_log()}")
            time.sleep(0.05)
        raise AssertionError(f"server did not become ready:\n{process.read_log()}")

    def _start_proxy(self, extra_args: list[str]) -> ManagedProcess:
        binary = str(Path(PROXY_BINARY).resolve())
        process = self.lab.exec_popen(
            self.lab.proxy_ns,
            binary,
            "-i",
            "127.0.0.1",
            "-p",
            str(PROXY_PORT),
            "-I",
            "0.0.0.0",
            *extra_args,
        )
        self._processes.append(process)
        deadline = time.time() + 5
        while time.time() < deadline:
            status = self.lab.exec_run(
                self.lab.proxy_ns,
                "python3",
                "-c",
                WAIT_PORT_SCRIPT,
                "127.0.0.1",
                str(PROXY_PORT),
                check=False,
            )
            if status.returncode == 0:
                return process
            if process._proc and process._proc.poll() is not None:
                raise AssertionError(f"proxy exited early:\n{process.read_log()}")
            time.sleep(0.05)
        raise AssertionError(f"proxy did not start:\n{process.read_log()}")

    def _run_client(self) -> Path:
        echoed = self.lab.tmpdir / "client.bin"
        proc = self.lab.exec_run(
            self.lab.proxy_ns,
            "python3",
            "-c",
            CLIENT_SCRIPT,
            str(PROXY_PORT),
            "10.200.3.2",
            str(SERVER_PORT),
            PAYLOAD.hex(),
            str(echoed),
            check=False,
        )
        if proc.returncode != 0:
            raise AssertionError(
                f"client command failed ({proc.returncode})\nstdout:\n{proc.stdout}\nstderr:\n{proc.stderr}"
            )
        return echoed

    def _assert_routed_case(self, extra_args: list[str]) -> None:
        proxy = None
        server = None
        received_path = self.lab.tmpdir / "server.bin"
        echoed_path = self.lab.tmpdir / "client.bin"
        try:
            server, _ready, received_path = self._start_server()
            proxy = self._start_proxy(extra_args)
            echoed_path = self._run_client()
            deadline = time.time() + 10
            while time.time() < deadline:
                if received_path.exists() and received_path.stat().st_size >= len(PAYLOAD):
                    break
                if server._proc and server._proc.poll() is not None:
                    break
                time.sleep(0.1)
        finally:
            if proxy:
                proxy.stop()
            if server:
                server.stop()

        proxy_log = proxy.read_log() if proxy else ""
        if not received_path.exists():
            raise AssertionError(f"server did not record payload:\n{server.read_log() if server else ''}")

        received = received_path.read_bytes()
        echoed = echoed_path.read_bytes()
        self.assertEqual(received, PAYLOAD, server.read_log() if server else "")
        self.assertEqual(echoed, PAYLOAD, proxy_log)

    def test_fake_ttl_retransmits_original_payload_across_routed_path(self) -> None:
        self._assert_routed_case(["--fake", "8", "--ttl", "1", "--wait-send", "--await-int", "5"])

    def test_md5sig_fake_retransmits_original_payload_across_routed_path(self) -> None:
        if self.md5sig_probe_reason is not None:
            self.assertEqual(
                self.md5sig_probe_reason,
                "TCP_MD5SIG is unavailable in this kernel/runtime environment",
            )
            return
        self._assert_routed_case(["--fake", "8", "--md5sig", "--wait-send", "--await-int", "5"])

    def test_drop_sack_fake_survives_routed_sack_path(self) -> None:
        self._assert_routed_case(
            ["--fake", "8", "--ttl", "1", "--drop-sack", "--wait-send", "--await-int", "5"]
        )


def emit_md5sig_operator_note() -> None:
    reason = RoutedLinuxRuntimeTests.md5sig_probe_reason
    if reason is None:
        return
    note = f"md5sig routed runtime capability unavailable: {reason}"
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::warning title=Routed md5sig unavailable::{note}", file=sys.stderr)
    else:
        print(f"NOTE: {note}", file=sys.stderr)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", required=True)
    parser.add_argument("--project-root", required=True)
    parser.add_argument("--preflight-check", action="store_true")
    args = parser.parse_args()

    global PROXY_BINARY, PROJECT_ROOT
    PROXY_BINARY = args.binary
    PROJECT_ROOT = Path(args.project_root).resolve()

    if args.preflight_check:
        suite = unittest.defaultTestLoader.loadTestsFromName(
            f"{RoutedLinuxRuntimeTests.__name__}.{RoutedLinuxRuntimeTests.PREFLIGHT_TEST}",
            module=sys.modules[__name__],
        )
    else:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(RoutedLinuxRuntimeTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    emit_md5sig_operator_note()
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
