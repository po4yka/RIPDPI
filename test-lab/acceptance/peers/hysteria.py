"""Real Hysteria release peer with local destination receipts; no network fetch at runtime."""

from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import platform
import secrets
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

VERSION = "app/v2.13.0"
# GitHub release API asset digests, app/v2.13.0, retrieved 2026-10-24.
# https://api.github.com/repos/HyNetworks/hysteria/releases/tags/app%2Fv2.13.0
DIGESTS = {
    "darwin-arm64": "c8c3589239ce4ecf335c07a3f6ec11b3ef8fbdfe9e5a5ea0ef8ba8ce50d2bb90",
    "linux-amd64": "907ba8c9693edb104b20582681fb7dc15639d5b64a9cbb616a7b539190a86691",
    "linux-arm64": "a68a61a84452ca250ce0368202521965ca9cc9d801a404f1dc9008ac6cf677a7",
}


def asset():
    machine = {"aarch64": "arm64", "x86_64": "amd64"}.get(
        platform.machine(), platform.machine()
    )
    name = f"{platform.system().lower()}-{machine}"
    if name not in DIGESTS:
        raise FileNotFoundError(f"no pinned Hysteria asset for {name}")
    return name


def binary(cache: Path, prepare=False):
    name = asset()
    path = cache / f"hysteria-v2.13.0-{name}"
    if prepare and not path.exists():
        cache.mkdir(parents=True, exist_ok=True)
        url = f"https://github.com/HyNetworks/hysteria/releases/download/{VERSION}/hysteria-{name}"
        with urllib.request.urlopen(url, timeout=120) as response:
            data = response.read(100 * 1024 * 1024)
        if hashlib.sha256(data).hexdigest() != DIGESTS[name]:
            raise ValueError("Hysteria release digest mismatch")
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as output:
            temporary = Path(output.name)
            output.write(data)
        temporary.chmod(0o700)
        temporary.replace(path)
    if not path.is_file():
        raise FileNotFoundError(
            "Hysteria peer is not prepared; run the scenario with --prepare"
        )
    if hashlib.sha256(path.read_bytes()).hexdigest() != DIGESTS[name]:
        raise ValueError("cached Hysteria peer digest mismatch")
    return path


def group_exists(pid):
    try:
        os.killpg(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        if sys.platform != 'darwin':
            raise
        # Darwin can deny a probe after a group is reaped. Only the kernel
        # process inventory can confirm that the group is absent.
        inventory = subprocess.check_output(['ps', '-axo', 'pgid='], text=True)
        return pid in {int(line.strip()) for line in inventory.splitlines() if line.strip()}


def stop_group(process):
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(process.pid, sig)
        except ProcessLookupError:
            pass
        except PermissionError:
            if group_exists(process.pid):
                raise
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            continue
    if process.poll() is None:
        raise RuntimeError("peer process did not stop")


class Destinations:
    """Record complete test payloads, then echo them through independent sockets."""

    def __init__(self, payload, *, persistent=False):
        self.payload = payload
        self.persistent = persistent
        self.tcp_connections = 0
        self.receipts = []
        self.errors = []
        self.stopped = threading.Event()
        self.tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        for sock in (self.tcp, self.udp):
            sock.bind(("127.0.0.1", 0))
            sock.settimeout(0.2)
        self.tcp.listen()
        self.threads = [
            threading.Thread(target=self.serve, args=(kind,), daemon=True)
            for kind in ("tcp", "udp")
        ]
        for thread in self.threads:
            thread.start()

    def serve(self, kind):
        sock = getattr(self, kind)
        while not self.stopped.is_set():
            try:
                if kind == "udp":
                    data, address = sock.recvfrom(65535)
                    self.record(kind, data)
                    sock.sendto(data, address)
                else:
                    conn, _ = sock.accept()
                    self.tcp_connections += 1
                    with conn:
                        conn.settimeout(0.2)
                        data = b""
                        while not self.stopped.is_set():
                            try:
                                part = conn.recv(len(self.payload) - len(data))
                            except socket.timeout:
                                continue
                            if not part:
                                break
                            data += part
                            if len(data) == len(self.payload):
                                self.record(kind, data)
                                conn.sendall(data)
                                data = b""
                                if not self.persistent:
                                    break
            except socket.timeout:
                continue
            except OSError as error:
                if not self.stopped.is_set():
                    self.errors.append(str(error))
                break

    def record(self, kind, data):
        self.receipts.append(
            {
                "transport": kind,
                "sha256": hashlib.sha256(data).hexdigest(),
                "bytes": len(data),
            }
        )

    def close(self):
        self.stopped.set()
        for sock in (self.tcp, self.udp):
            sock.close()
        for thread in self.threads:
            thread.join(timeout=3)
        if any(thread.is_alive() for thread in self.threads):
            raise RuntimeError("destination worker did not stop")


@contextmanager
def peer(cache, out_dir, run_id, cleanup, *, ipv6=False, salamander=False, persistent=False, payload_bytes=0):
    executable = binary(cache)
    payload = f"ripdpi-acceptance:{run_id}".encode()
    if not 0 <= payload_bytes <= 60_000:
        raise ValueError("peer payload size exceeds the UDP test bound")
    if payload_bytes:
        payload = (payload * ((payload_bytes + len(payload) - 1) // len(payload)))[:payload_bytes]
    destinations = Destinations(payload, persistent=persistent)
    host = "::1" if ipv6 else "127.0.0.1"
    family = socket.AF_INET6 if ipv6 else socket.AF_INET
    def address(port):
        return f"[{host}]:{port}" if ipv6 else f"{host}:{port}"
    cleanup["passed"] = False
    try:
        with tempfile.TemporaryDirectory(prefix="ripdpi-hysteria-peer-") as directory:
            private = Path(directory)
            cert, key = private / "cert.pem", private / "key.pem"
            cert_config = private / "certificate.cnf"
            cert_config.write_text(
                "[req]\ndistinguished_name=dn\nx509_extensions=extensions\nprompt=no\n"
                "[dn]\nCN=localhost\n[extensions]\nbasicConstraints=critical,CA:FALSE\n"
                "keyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n"
                "subjectAltName=DNS:localhost\n"
            )
            subprocess.run(
                [
                    "openssl",
                    "req",
                    "-x509",
                    "-newkey",
                    "rsa:2048",
                    "-nodes",
                    "-days",
                    "1",
                    "-config",
                    str(cert_config),
                    "-keyout",
                    str(key),
                    "-out",
                    str(cert),
                ],
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=30,
            )
            with socket.socket(family, socket.SOCK_DGRAM) as reservation:
                reservation.bind((host, 0))
                port = reservation.getsockname()[1]
            auth = secrets.token_hex(24)
            obfs = secrets.token_hex(24) if salamander else ""
            config = private / "server.json"
            config.write_text(
                json.dumps(
                    {
                        "listen": address(port),
                        **({"obfs": {"type": "salamander", "salamander": {"password": obfs}}} if salamander else {}),
                        "tls": {"cert": str(cert), "key": str(key)},
                        "auth": {"type": "password", "password": auth},
                        "disableUDP": False,
                    }
                )
            )
            config.chmod(0o600)
            log_path = out_dir / "peer.log"
            with log_path.open("w") as log:
                with subprocess.Popen(
                    [str(executable), "server", "-c", str(config)],
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    start_new_session=True,
                ) as process:
                    try:
                        deadline = time.monotonic() + 10
                        while "server up and running" not in log_path.read_text():
                            if process.poll() is not None:
                                raise RuntimeError(
                                    "Hysteria exited before readiness; see private peer.log"
                                )
                            if time.monotonic() >= deadline:
                                raise TimeoutError(
                                    "Hysteria readiness deadline exceeded"
                                )
                            time.sleep(0.05)
                        yield {
                            "env": {
                                "RIPDPI_HYSTERIA_ENDPOINT": address(port),
                                "RIPDPI_HYSTERIA_SALAMANDER": obfs,
                                "RIPDPI_HYSTERIA_AUTH": auth,
                                "RIPDPI_HYSTERIA_TCP": f"127.0.0.1:{destinations.tcp.getsockname()[1]}",
                                "RIPDPI_HYSTERIA_UDP": f"127.0.0.1:{destinations.udp.getsockname()[1]}",
                                "RIPDPI_HYSTERIA_PAYLOAD": payload.decode(),
                            },
                            "destinations": destinations,
                            "server_address": (host, port),
                            "receipts": destinations.receipts,
                            "errors": destinations.errors,
                            "sha256": DIGESTS[asset()],
                            "payload_sha256": hashlib.sha256(payload).hexdigest(),
                        }
                    finally:
                        stop_group(process)
    finally:
        destinations.close()
        cleanup["passed"] = True
