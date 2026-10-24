"""Owned Hysteria2 server and a device-scoped UDP fault gate."""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
import platform
from pathlib import Path
import secrets
import selectors
import signal
import socket
import ssl
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import urllib.request

from android_udp_bridge import MAX_FLOWS

HYSTERIA_DIGESTS = {
    (
        "Darwin",
        "arm64",
    ): "c8c3589239ce4ecf335c07a3f6ec11b3ef8fbdfe9e5a5ea0ef8ba8ce50d2bb90",
    (
        "Linux",
        "aarch64",
    ): "a68a61a84452ca250ce0368202521965ca9cc9d801a404f1dc9008ac6cf677a7",
    (
        "Linux",
        "x86_64",
    ): "907ba8c9693edb104b20582681fb7dc15639d5b64a9cbb616a7b539190a86691",
}


def verify_binary(path: Path) -> None:
    expected = HYSTERIA_DIGESTS.get((platform.system(), platform.machine()))
    if (
        expected is None
        or path.is_symlink()
        or hashlib.sha256(path.read_bytes()).hexdigest() != expected
    ):
        raise ValueError("Hysteria peer must match the pinned v2.13.0 release artifact")


def private_ipv4(value: str) -> str:
    address = ipaddress.IPv4Address(value)
    ranges = ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8")
    if not any(address in ipaddress.IPv4Network(cidr) for cidr in ranges):
        raise ValueError("fixture addresses must be private IPv4")
    return str(address)


def owned_dns_outbound(manifest: dict) -> dict:
    # Validate before creating files or starting any fixture process.
    port = manifest.get("hysteriaSocksPort")
    dns_port = manifest.get("dnsPort")
    if any(
        type(value) is not int or not 1 <= value <= 65535 for value in (port, dns_port)
    ):
        raise ValueError("owned Hysteria DNS requires valid SOCKS and DNS ports")
    return {
        "name": "owned_dns",
        "type": "socks5",
        "socks5": {"addr": f"127.0.0.1:{port}"},
    }


class UdpFlowLimitError(RuntimeError):
    """The owned gate has no capacity for another source socket."""


class UdpGate:
    def __init__(self, host: str, device: str, upstream: tuple[str, int]):
        self.device = private_ipv4(device)
        self.upstream = upstream
        self.listener = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.listener.bind((private_ipv4(host), 0))
        self.listener.setblocking(False)
        self.port = self.listener.getsockname()[1]
        self.selector = selectors.DefaultSelector()
        self.selector.register(self.listener, selectors.EVENT_READ, None)
        self.connections: dict[tuple[str, int], socket.socket] = {}
        self.running = threading.Event()
        self.running.set()
        self.stopped = threading.Event()
        self.error: Exception | None = None
        self.sockets_closed = False
        self.forwarded = 0
        self.dropped = 0
        self.thread = threading.Thread(
            target=self.serve, name="owned-udp-gate", daemon=True
        )
        self.thread.start()

    def serve(self):
        try:
            while not self.stopped.is_set():
                for key, _ in self.selector.select(0.1):
                    try:
                        packet, source = key.fileobj.recvfrom(65535)
                    except BlockingIOError:
                        continue
                    if not self.running.is_set():
                        self.dropped += 1
                        continue
                    if key.data is None:
                        if source[0] != self.device:
                            self.dropped += 1
                            continue
                        connection = self.connections.get(source)
                        if connection is None:
                            if len(self.connections) >= MAX_FLOWS:
                                raise UdpFlowLimitError(
                                    "owned UDP client socket bound exceeded"
                                )
                            connection = socket.socket(
                                socket.AF_INET, socket.SOCK_DGRAM
                            )
                            connection.bind(("127.0.0.1", 0))
                            connection.connect(self.upstream)
                            connection.setblocking(False)
                            self.connections[source] = connection
                            self.selector.register(
                                connection, selectors.EVENT_READ, source
                            )
                        connection.send(packet)
                    else:
                        self.listener.sendto(packet, key.data)
                    self.forwarded += 1
        except Exception as error:
            self.error = error

    def receipts(self):
        return {
            "forwarded": self.forwarded,
            "dropped": self.dropped,
            "flows": len(self.connections),
            "flowLimit": MAX_FLOWS,
            "errorCategory": (
                "flow-limit"
                if isinstance(self.error, UdpFlowLimitError)
                else "worker-failed" if self.error is not None else None
            ),
            "workerAlive": self.thread.is_alive(),
            "socketsClosed": self.sockets_closed,
        }

    def close(self):
        self.stopped.set()
        self.thread.join(timeout=3)
        if self.thread.is_alive():
            raise RuntimeError("UDP gate did not stop")
        self.selector.close()
        self.listener.close()
        for connection in self.connections.values():
            connection.close()
        self.sockets_closed = True
        if self.error is not None:
            raise RuntimeError("UDP gate failed") from self.error


def owned_https_acl(manifest):
    port = manifest["qualityPort"]
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError("owned HTTPS port must be an integer in 1..65535")
    return [
        f"direct({host}, tcp/{port}, 127.0.0.1)"
        for host in ("quality-a.example.test", "quality-b.example.test")
    ]


class HysteriaFixture:
    def __init__(
        self,
        directory: Path,
        tls_directory: Path,
        executable: Path,
        host: str,
        device: str,
        manifest: dict,
        peer_control_port: int,
        advertise_host: str | None = None,
    ):
        self.directory = directory
        self.tls_directory = tls_directory
        self.executable = executable
        self.host = private_ipv4(host)
        self.advertise_host = private_ipv4(advertise_host or host)
        self.device = private_ipv4(device)
        self.manifest = dict(manifest)
        self.peer_control_port = peer_control_port
        self.process = None
        self.gate = None
        self.control = None
        self.control_thread = None
        self.log = None
        self.password = secrets.token_hex(24)
        self.salamander = secrets.token_hex(24)
        self.port = 0

    def start(self):
        dns_outbound = owned_dns_outbound(self.manifest)
        verify_binary(self.executable)
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=False)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as reserve:
            reserve.bind(("127.0.0.1", 0))
            server_port = reserve.getsockname()[1]
        configuration = {
            "listen": f"127.0.0.1:{server_port}",
            "tls": {
                "cert": str(self.tls_directory / "certificate.pem"),
                "key": str(self.tls_directory / "private-key.pem"),
            },
            "auth": {"type": "password", "password": self.password},
            "obfs": {"type": "salamander", "salamander": {"password": self.salamander}},
            "disableUDP": False,
            "outbounds": [dns_outbound, {"name": "direct", "type": "direct"}],
            "acl": {
                "inline": [
                    *owned_https_acl(self.manifest),
                    f"direct(192.0.2.77, tcp/{self.manifest['transportPort']}, 127.0.0.1)",
                    f"owned_dns(192.0.2.53, udp/{self.manifest['dnsPort']})",
                    "reject(all)",
                ]
            },
        }
        config_path = self.directory / "server.json"
        config_path.write_text(json.dumps(configuration))
        config_path.chmod(0o600)
        self.log = (self.directory / "server.log").open("w")
        try:
            self.process = subprocess.Popen(
                [str(self.executable), "server", "-c", str(config_path)],
                stdout=self.log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
            deadline = time.monotonic() + 15
            while (
                "server up and running"
                not in (self.directory / "server.log").read_text()
            ):
                if self.process.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError("owned Hysteria peer failed readiness")
                time.sleep(0.05)
            self.gate = UdpGate(self.host, self.device, ("127.0.0.1", server_port))
            der = ssl.PEM_cert_to_DER_cert(
                (self.tls_directory / "certificate.pem").read_text()
            )
            self.manifest.update(
                hysteriaHost=self.advertise_host,
                hysteriaPort=self.gate.port,
                hysteriaPassword=self.password,
                hysteriaSalamander=self.salamander,
                hysteriaCertificatePin=hashlib.sha256(der).hexdigest(),
            )
            owner = self

            class Handler(BaseHTTPRequestHandler):
                def log_message(self, *_):
                    pass

                def do_GET(self):
                    if self.path == "/manifest":
                        self.send_json(owner.manifest)
                    elif self.path in (
                        "/transport-receipts",
                        "/dns-receipts",
                        "/receipts",
                        "/direct-receipts",
                        "/dns-http-receipts",
                        "/request-receipts",
                    ):
                        opener = urllib.request.build_opener(
                            urllib.request.ProxyHandler({})
                        )
                        with opener.open(
                            f"http://127.0.0.1:{owner.peer_control_port}{self.path}",
                            timeout=3,
                        ) as response:
                            self.send_json(json.load(response))
                    elif self.path == "/udp-receipts":
                        self.send_json(owner.gate.receipts())
                    else:
                        self.send_error(404)

                def do_POST(self):
                    if self.path == "/peer/stop":
                        owner.gate.running.clear()
                    elif self.path == "/peer/start":
                        owner.gate.running.set()
                    else:
                        self.send_error(404)
                        return
                    self.send_json({"running": owner.gate.running.is_set()})

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
                target=self.control.serve_forever,
                name="owned-hysteria-control",
                daemon=True,
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
                if self.control_thread.is_alive():
                    raise RuntimeError("Hysteria control did not stop")
            except Exception as error:
                errors.append(error)
            self.control = None
        if self.gate is not None:
            gate, self.gate = self.gate, None
            try:
                gate.close()
            except Exception as error:
                errors.append(error)
            try:
                (self.directory / "udp-receipts.json").write_text(
                    json.dumps(gate.receipts(), indent=2) + "\n"
                )
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
            raise RuntimeError("owned Hysteria cleanup failed") from errors[0]


def prepare_binary(path: Path) -> None:
    target = {
        ("Darwin", "arm64"): "darwin-arm64",
        ("Linux", "aarch64"): "linux-arm64",
        ("Linux", "x86_64"): "linux-amd64",
    }.get((platform.system(), platform.machine()))
    if target is None:
        raise ValueError("unsupported Hysteria acceptance host")
    url = f"https://github.com/apernet/hysteria/releases/download/app%2Fv2.13.0/hysteria-{target}"
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with (
            os.fdopen(descriptor, "wb") as output,
            urllib.request.urlopen(url, timeout=60) as response,
        ):
            total = 0
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                if total > 64 * 1024 * 1024:
                    raise ValueError("Hysteria release artifact exceeds its size limit")
                output.write(chunk)
        verify_binary(path)
        path.chmod(0o700)
    except BaseException:
        path.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare-binary", required=True, type=Path)
    prepare_binary(parser.parse_args().prepare_binary)
