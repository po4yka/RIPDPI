"""Owned NaiveProxy server (Caddy + klzgrad/forwardproxy) with a lab-CA-signed leaf.

The server presents a leaf signed by a one-day lab CA. The Rust helper trusts the leaf through
the per-profile SHA-256 pin. The upstream client has no pin; the emulator trusts the lab CA
through a tmpfs overlay of /system/etc/security/cacerts (LabTrust). The server forwards only to a loopback CONNECT mapper that
admits the one owned destination and counts the tunnels it opens. Credentials stay in a
private Caddyfile and in the manifest served on the owned control port; they never reach argv.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import ssl
import subprocess
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from android_hysteria_fixture import private_ipv4

SERVER_NAME = "naive.test"
DESTINATION_HOST = "192.0.2.77"
RECEIPT_PATHS = (
    "/transport-receipts",
    "/dns-receipts",
    "/receipts",
    "/direct-receipts",
    "/dns-http-receipts",
    "/request-receipts",
)


def verify_binary(path: Path) -> str:
    """Return the digest of a prepared server whose recorded digest matches."""
    record = path.parent / "peer.json"
    if path.is_symlink() or not path.is_file() or not record.is_file():
        raise ValueError("NaiveProxy peer must be a prepared, recorded binary")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if json.loads(record.read_text()).get("sha256") != digest:
        raise ValueError("NaiveProxy peer digest differs from its prepared record")
    return digest


def _openssl(*args: str, stdin: bytes | None = None) -> bytes:
    return subprocess.run(
        ["openssl", *args], check=True, capture_output=True, timeout=60, input=stdin
    ).stdout


def lab_ca(directory: Path) -> tuple[Path, Path]:
    """Create a one-day lab CA under a new private directory and return (certificate, key)."""
    directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    cert, key = directory / "ca.pem", directory / "ca.key"
    _openssl(
        "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
        "-nodes", "-days", "1", "-subj", "/CN=RIPDPI acceptance lab CA",
        "-addext", "basicConstraints=critical,CA:TRUE,pathlen:0",
        "-addext", "keyUsage=critical,keyCertSign,cRLSign",
        "-keyout", str(key), "-out", str(cert),
    )
    key.chmod(0o600)
    return cert, key


def signed_leaf(directory: Path, ca_cert: Path, ca_key: Path) -> tuple[Path, Path, str]:
    """Issue a one-day server leaf from the lab CA; return (certificate, key, SHA-256 of its DER)."""
    cert, key = directory / "leaf.pem", directory / "leaf.key"
    request, extensions = directory / "leaf.csr", directory / "leaf.ext"
    _openssl(
        "req", "-new", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
        "-nodes", "-subj", f"/CN={SERVER_NAME}", "-keyout", str(key), "-out", str(request),
    )
    key.chmod(0o600)
    extensions.write_text(
        f"subjectAltName=DNS:{SERVER_NAME}\nbasicConstraints=CA:FALSE\n"
        "keyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\n"
    )
    _openssl(
        "x509", "-req", "-in", str(request), "-CA", str(ca_cert), "-CAkey", str(ca_key),
        "-CAcreateserial", "-days", "1", "-extfile", str(extensions), "-out", str(cert),
    )
    der = ssl.PEM_cert_to_DER_cert(cert.read_text())
    return cert, key, hashlib.sha256(der).hexdigest()


SYSTEM_CACERTS = "/system/etc/security/cacerts"


class LabTrust:
    """Make an emulator trust the lab CA by overlaying a tmpfs on the system CA directory.

    Run before the app is installed: `adb root` restarts adbd and kills a running instrumentation.
    The overlay lives in the device's global mount namespace and is gone after a reboot; remove()
    unmounts it earlier. Neither the app nor its trust configuration changes.
    """

    def __init__(self, adb: list[str], ca_cert: Path):
        self.adb = adb
        self.ca_cert = ca_cert
        self.mounted = False

    def _run(self, *args: str, timeout: int = 60) -> str:
        return subprocess.run(
            [*self.adb, *args], check=True, capture_output=True, text=True, timeout=timeout
        ).stdout

    def install(self) -> str:
        """Mount the overlay and return the hash-named file that carries the lab CA."""
        name = _openssl("x509", "-in", str(self.ca_cert), "-noout", "-subject_hash_old").decode().strip() + ".0"
        self._run("root")
        self._run("wait-for-device")
        if self._run("shell", "id", "-u").strip() != "0":
            raise RuntimeError("lab trust needs adb root on the emulator")
        if self._run("shell", f"grep -c ' {SYSTEM_CACERTS} tmpfs ' /proc/mounts || true").strip() != "0":
            raise RuntimeError("a CA overlay is already mounted; remove it or reboot the emulator")
        stage = "/data/local/tmp/ripdpi-lab-cacerts"
        self._run("shell", f"rm -rf {stage} && mkdir -p {stage} && cp {SYSTEM_CACERTS}/* {stage}/")
        self._run("push", str(self.ca_cert), f"{stage}/{name}")
        self.mounted = True
        self._run(
            "shell",
            f"mount -t tmpfs tmpfs {SYSTEM_CACERTS} && cp {stage}/* {SYSTEM_CACERTS}/ "
            f"&& chown root:root {SYSTEM_CACERTS}/* && chmod 644 {SYSTEM_CACERTS}/* "
            f"&& chcon u:object_r:system_file:s0 {SYSTEM_CACERTS} {SYSTEM_CACERTS}/* "
            f"&& rm -rf {stage}",
        )
        if self._run("shell", f"ls {SYSTEM_CACERTS}/{name}").strip() != f"{SYSTEM_CACERTS}/{name}":
            raise RuntimeError("lab CA is absent from the overlay")
        return name

    def remove(self):
        if self.mounted:
            self._run("shell", f"umount {SYSTEM_CACERTS}")
            self.mounted = False


class ConnectMapper:
    """Loopback HTTP CONNECT upstream: admits one owned destination and counts tunnels."""

    def __init__(self, destination_port: int):
        self.destination_port = destination_port
        self.connects = 0
        self.rejected = 0
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(32)
        self.port = self.listener.getsockname()[1]
        self.lock = threading.Lock()
        self.thread = threading.Thread(target=self.serve, name="owned-naive-mapper", daemon=True)
        self.thread.start()

    def serve(self):
        while True:
            try:
                client, _ = self.listener.accept()
            except OSError:
                return
            threading.Thread(target=self.handle, args=(client,), daemon=True).start()

    def handle(self, client: socket.socket):
        upstream = None
        try:
            client.settimeout(10)
            head = b""
            while b"\r\n\r\n" not in head and len(head) < 8192:
                chunk = client.recv(4096)
                if not chunk:
                    return
                head += chunk
            method, target, *_ = head.split(b"\r\n", 1)[0].decode("latin-1").split(" ")
            if method != "CONNECT" or target != f"{DESTINATION_HOST}:{self.destination_port}":
                with self.lock:
                    self.rejected += 1
                client.sendall(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n")
                return
            upstream = socket.create_connection(("127.0.0.1", self.destination_port), timeout=5)
            with self.lock:
                self.connects += 1
            client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            client.settimeout(None)
            upstream.settimeout(None)
            pump = threading.Thread(target=self.copy, args=(upstream, client), daemon=True)
            pump.start()
            self.copy(client, upstream)
            pump.join(timeout=5)
        except OSError:
            pass
        finally:
            for sock in (client, upstream):
                if sock is not None:
                    try:
                        sock.close()
                    except OSError:
                        pass

    @staticmethod
    def copy(source: socket.socket, sink: socket.socket):
        try:
            while data := source.recv(65536):
                sink.sendall(data)
        except OSError:
            pass
        try:
            sink.shutdown(socket.SHUT_WR)
        except OSError:
            pass

    def receipts(self) -> dict:
        with self.lock:
            return {"connects": self.connects, "rejected": self.rejected}

    def close(self):
        self.listener.close()
        self.thread.join(timeout=3)


class NaiveProxyFixture:
    def __init__(
        self,
        directory: Path,
        executable: Path,
        host: str,
        manifest: dict,
        peer_control_port: int,
        advertise_host: str | None = None,
        ca: tuple[Path, Path] | None = None,
    ):
        self.directory = directory
        self.ca = ca
        self.executable = executable
        self.host = private_ipv4(host)
        self.advertise_host = private_ipv4(advertise_host or host)
        self.manifest = dict(manifest)
        self.peer_control_port = peer_control_port
        self.user = "ripdpi"
        self.password = secrets.token_hex(24)
        self.process = None
        self.mapper = None
        self.control = None
        self.control_thread = None
        self.log = None
        self.port = 0
        self.server_port = 0

    def start(self):
        port = self.manifest.get("transportPort")
        if type(port) is not int or not 1 <= port <= 65535:
            raise ValueError("owned NaiveProxy destination requires a valid transport port")
        digest = verify_binary(self.executable)
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=False)
        try:
            ca_cert, ca_key = self.ca or lab_ca(self.directory / "ca")
            cert, key, pin = signed_leaf(self.directory, ca_cert, ca_key)
            self.mapper = ConnectMapper(port)
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as reserve:
                reserve.bind((self.host, 0))
                self.server_port = reserve.getsockname()[1]
            config = self.directory / "Caddyfile"
            config.write_text(
                "{\n\tadmin off\n\tauto_https off\n}\n"
                f":{self.server_port} {{\n\tbind {self.host}\n\ttls {cert} {key}\n\troute {{\n"
                f"\t\tforward_proxy {{\n\t\t\tbasic_auth {self.user} {self.password}\n"
                "\t\t\thide_ip\n\t\t\thide_via\n"
                f"\t\t\tupstream http://127.0.0.1:{self.mapper.port}\n"
                f"\t\t\tacl {{\n\t\t\t\tallow {DESTINATION_HOST}\n\t\t\t\tdeny all\n\t\t\t}}\n\t\t}}\n"
                '\t\trespond "ripdpi cover site" 200\n\t}\n}\n'
            )
            config.chmod(0o600)
            self.log = (self.directory / "server.log").open("w")
            home = str(self.directory)
            self.process = subprocess.Popen(
                [str(self.executable), "run", "--config", str(config), "--adapter", "caddyfile"],
                stdout=self.log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
                env=dict(os.environ, HOME=home, XDG_DATA_HOME=home, XDG_CONFIG_HOME=home),
            )
            deadline = time.monotonic() + 15
            while True:
                if self.process.poll() is not None:
                    raise RuntimeError("owned NaiveProxy peer exited before readiness")
                try:
                    socket.create_connection((self.host, self.server_port), timeout=0.2).close()
                    break
                except OSError:
                    if time.monotonic() >= deadline:
                        raise RuntimeError("owned NaiveProxy peer failed readiness")
                    time.sleep(0.05)
            self.manifest.update(
                naiveHost=self.advertise_host,
                naivePort=self.server_port,
                naiveUser=self.user,
                naivePassword=self.password,
                naivePin=pin,
                naiveServerName=SERVER_NAME,
                naiveBinarySha256=digest,
            )
            owner = self

            class Handler(BaseHTTPRequestHandler):
                def log_message(self, *_):
                    pass

                def do_GET(self):
                    if self.path == "/manifest":
                        self.send_json(owner.manifest)
                    elif self.path in RECEIPT_PATHS:
                        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                        with opener.open(
                            f"http://127.0.0.1:{owner.peer_control_port}{self.path}", timeout=3
                        ) as response:
                            self.send_json(json.load(response))
                    elif self.path == "/naive-receipts":
                        self.send_json(owner.mapper.receipts())
                    else:
                        self.send_error(404)

                def send_json(self, value):
                    body = json.dumps(value).encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)

            self.control = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            self.port = self.control.server_port
            self.control_thread = threading.Thread(
                target=self.control.serve_forever, name="owned-naive-control", daemon=True
            )
            self.control_thread.start()
        except BaseException:
            self.close()
            raise

    def close(self):
        errors = []
        if self.control is not None:
            try:
                self.control.shutdown()
                self.control.server_close()
                self.control_thread.join(timeout=3)
            except Exception as error:
                errors.append(error)
            self.control = None
        if self.mapper is not None:
            mapper, self.mapper = self.mapper, None
            try:
                (self.directory / "naive-receipts.json").write_text(json.dumps(mapper.receipts(), indent=2) + "\n")
                mapper.close()
            except Exception as error:
                errors.append(error)
        if self.process is not None:
            try:
                if self.process.poll() is None:
                    os.killpg(self.process.pid, signal.SIGTERM)
                    try:
                        self.process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        os.killpg(self.process.pid, signal.SIGKILL)
                        self.process.wait(timeout=5)
            except Exception as error:
                errors.append(error)
            self.process = None
        if self.log is not None:
            self.log.close()
            self.log = None
        if errors:
            raise RuntimeError("owned NaiveProxy cleanup failed") from errors[0]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-binary", required=True, type=Path)
    print(verify_binary(parser.parse_args().verify_binary))
