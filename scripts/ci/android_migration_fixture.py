"""Independent HTTPS delivery origin for owned Android migration acceptance."""

from __future__ import annotations

import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import ssl
import threading


MAX_PAYLOAD = 1024 * 1024
MAX_RECEIPTS = 256


def admitted_payload(value: bytes) -> bytes:
    if not isinstance(value, bytes) or not 0 < len(value) <= MAX_PAYLOAD:
        raise ValueError("migration fixture payload must be bounded bytes")
    document = json.loads(value)
    if (
        not isinstance(document, dict)
        or not isinstance(document.get("ripdpi"), dict)
        or type(document["ripdpi"].get("schema_version")) is not int
        or document["ripdpi"]["schema_version"] != 2
    ):
        raise ValueError("migration fixture requires bundle version 2")
    return value


class MigrationFixture:
    """Own two loopback listeners; peer lifecycle cannot stop the update origin."""

    def __init__(self, directory: Path, tls_directory: Path, payload_factory):
        self.directory = Path(directory)
        self.tls_directory = Path(tls_directory)
        self.payload_factory = payload_factory
        self.port = 0
        self.control_port = 0
        self.mirror_token = secrets.token_hex(32)
        self._lock = threading.Lock()
        self._revision = 0
        self._subscription_running = True
        self._receipts = []
        self._servers = []
        self._threads = []
        self._started = False
        self._directory_owned = False
        self._receipts_persisted = False

    def receipts(self) -> list[dict]:
        with self._lock:
            return [dict(receipt) for receipt in self._receipts]

    def start(self) -> None:
        if self._started:
            raise RuntimeError("migration fixture cannot restart")
        self._started = True
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=False)
        self._directory_owned = True
        certificate = self.tls_directory / "certificate.pem"
        key = self.tls_directory / "private-key.pem"
        for path in (certificate, key):
            if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077:
                raise ValueError("migration TLS files must be private regular files")
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(certificate, key)
        owner = self

        class TlsServer(ThreadingHTTPServer):
            def get_request(self):
                connection, address = super().get_request()
                connection.settimeout(3)
                try:
                    return context.wrap_socket(connection, server_side=True), address
                except BaseException:
                    connection.close()
                    raise

        class Handler(BaseHTTPRequestHandler):
            def setup(self):
                self.request.settimeout(3)
                super().setup()

            def log_message(self, *_):
                pass

            def respond(self, status, body, content_type="application/json"):
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(body)))
                # This listener closes each response; clients must not pool the socket.
                self.send_header("Connection", "close")
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                if self.server is owner._control:
                    if self.path == "/receipts":
                        self.respond(200, json.dumps(owner.receipts()).encode())
                    elif self.path == "/manifest":
                        with owner._lock:
                            body = json.dumps({
                                "port": owner.port,
                                "controlPort": owner.control_port,
                                "revision": owner._revision,
                            }).encode()
                        self.respond(200, body)
                    else:
                        self.respond(404, b"{}")
                    return
                with owner._lock:
                    if len(owner._receipts) >= MAX_RECEIPTS:
                        status, body = 503, b"{}"
                    else:
                        authorized = self.path == "/subscription" or (
                            self.path == "/mirror"
                            and len(self.headers.get_all("Authorization", [])) == 1
                            and hmac.compare_digest(
                                self.headers.get("Authorization", "").encode("utf-8"),
                                ("Bearer " + owner.mirror_token).encode("ascii"),
                            )
                        )
                        status = 200
                        if self.path not in ("/subscription", "/mirror"):
                            status = 404
                        elif not authorized:
                            status = 401
                        elif self.path == "/subscription" and not owner._subscription_running:
                            status = 503
                        body = owner._payloads[owner._revision] if status == 200 else b"{}"
                        owner._receipts.append({
                            "sequence": len(owner._receipts) + 1,
                            "revision": owner._revision,
                            "path": self.path if self.path in ("/subscription", "/mirror") else "unknown",
                            "authorized": authorized,
                            "status": status,
                            "sha256": hashlib.sha256(body).hexdigest() if status == 200 else None,
                        })
                self.respond(status, body)

            def do_POST(self):
                if self.server is not owner._control:
                    self.respond(405, b"{}")
                    return
                with owner._lock:
                    if self.path == "/revision/next":
                        owner._revision = 1
                    elif self.path == "/subscription/stop":
                        owner._subscription_running = False
                    else:
                        self.respond(404, b"{}")
                        return
                    body = json.dumps({"revision": owner._revision}).encode()
                self.respond(200, body)

        try:
            self._tls = TlsServer(("127.0.0.1", 0), Handler)
            self._servers.append(self._tls)
            self.port = self._tls.server_port
            supplied = self.payload_factory(self.port, self.mirror_token)
            if not isinstance(supplied, tuple) or len(supplied) != 2:
                raise ValueError("migration fixture requires two revisions")
            self._payloads = tuple(admitted_payload(payload) for payload in supplied)
            if self._payloads[0] == self._payloads[1]:
                raise ValueError("migration revisions must differ")
            self._control = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            self._servers.append(self._control)
            self.control_port = self._control.server_port
            for server in self._servers:
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                self._threads.append(thread)
        except BaseException:
            self.close()
            raise

    def close(self) -> None:
        try:
            for server, thread in zip(self._servers, self._threads):
                server.shutdown()
                thread.join(timeout=5)
                if thread.is_alive():
                    raise RuntimeError("migration listener did not stop")
        finally:
            try:
                for server in self._servers:
                    server.server_close()
            finally:
                self._servers.clear()
                self._threads.clear()
                if self._directory_owned and not self._receipts_persisted:
                    descriptor = os.open(
                        self.directory / "http-receipts.json",
                        os.O_WRONLY | os.O_CREAT | os.O_EXCL,
                        0o600,
                    )
                    with os.fdopen(descriptor, "w") as stream:
                        json.dump(self.receipts(), stream)
                    self._receipts_persisted = True
