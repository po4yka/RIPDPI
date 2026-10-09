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

VERSION = "app/v2.9.0"
# GitHub release API asset digests, app/v2.9.0, retrieved 2026-10-08.
# https://api.github.com/repos/HyNetworks/hysteria/releases/tags/app%2Fv2.9.0
DIGESTS = {
    "darwin-arm64": "9e49645c7363bf630815d26e5d512a19f41c9d50624ca6960d2b48d95c33dcba",
    "linux-amd64": "8225c8380f1ae8122921d4986c2b70976e9c6e6f87a977e7d0498a813f4f3e37",
    "linux-arm64": "a3ccc0a3e791b85710ef63d22304a6f7dea210259be71bcd70cf9a210a385454",
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
    path = cache / f"hysteria-v2.9.0-{name}"
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

    def __init__(self, payload):
        self.payload = payload
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
                    with conn:
                        conn.settimeout(2)
                        data = b""
                        while len(data) < len(self.payload):
                            part = conn.recv(len(self.payload) - len(data))
                            if not part:
                                break
                            data += part
                        self.record(kind, data)
                        conn.sendall(data)
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
def peer(cache, out_dir, run_id, cleanup):
    executable = binary(cache)
    payload = f"ripdpi-acceptance:{run_id}".encode()
    destinations = Destinations(payload)
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
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as reservation:
                reservation.bind(("127.0.0.1", 0))
                port = reservation.getsockname()[1]
            auth = secrets.token_hex(24)
            config = private / "server.json"
            config.write_text(
                json.dumps(
                    {
                        "listen": f"127.0.0.1:{port}",
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
                                "RIPDPI_HYSTERIA_ENDPOINT": f"127.0.0.1:{port}",
                                "RIPDPI_HYSTERIA_AUTH": auth,
                                "RIPDPI_HYSTERIA_TCP": f"127.0.0.1:{destinations.tcp.getsockname()[1]}",
                                "RIPDPI_HYSTERIA_UDP": f"127.0.0.1:{destinations.udp.getsockname()[1]}",
                                "RIPDPI_HYSTERIA_PAYLOAD": payload.decode(),
                            },
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
