"""Owned nginx TLS frontend for the real Android XHTTP acceptance path."""

from __future__ import annotations

import hashlib
import ipaddress
import argparse
import json
import os
from pathlib import Path
import socket
import signal
import shutil
import tempfile
import ssl
import subprocess
import sys
import time
import uuid

NGINX_IMAGE = (
    "nginx@sha256:30f1c0d78e0ad60901648be663a710bdadf19e4c10ac6782c235200619158284"
)


def private_host(value: str) -> str:
    address = ipaddress.IPv4Address(value)
    if not any(
        address in ipaddress.IPv4Network(cidr)
        for cidr in ("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
    ):
        raise ValueError("nginx listener must be private IPv4")
    return str(address)


def configuration(upstream: str, port: int, listen: str) -> str:
    if (
        upstream not in {"127.0.0.1", "host.docker.internal"}
        or type(port) is not int
        or not 1 <= port <= 65535
    ):
        raise ValueError("invalid owned nginx upstream")
    if listen != "8443":
        host, separator, endpoint = listen.partition(":")
        private_host(host)
        if not separator or not endpoint.isdigit() or not 1 <= int(endpoint) <= 65535:
            raise ValueError("invalid owned nginx listener")
    return f"""worker_processes 1;
error_log /dev/stderr warn;
pid /tmp/nginx.pid;
events {{ worker_connections 128; }}
http {{
    access_log off;
    client_body_temp_path /tmp/client;
    proxy_temp_path /tmp/proxy;
    fastcgi_temp_path /tmp/fastcgi;
    uwsgi_temp_path /tmp/uwsgi;
    scgi_temp_path /tmp/scgi;
    server {{
        listen {listen} ssl;
        http2 on;
        server_name fixture.test;
        ssl_certificate /fixture/tls/certificate.pem;
        ssl_certificate_key /fixture/tls/private-key.pem;
        ssl_protocols TLSv1.3;
        client_max_body_size 16m;
        location /owned-xhttp {{
            proxy_http_version 1.1;
            proxy_set_header Host $host;
            proxy_set_header Connection "";
            proxy_set_header X-Forwarded-Proto https;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $remote_addr;
            proxy_set_header Forwarded "";
            proxy_buffering off;
            proxy_request_buffering off;
            proxy_read_timeout 180s;
            proxy_send_timeout 180s;
            proxy_pass http://{upstream}:{port};
        }}
        location / {{ return 404; }}
    }}
}}
"""


class NginxFixture:
    """Creates one identified container and removes only that container."""

    def __init__(
        self,
        directory: Path,
        tls_directory: Path,
        upstream_port: int,
        bind_host: str = "127.0.0.1",
    ):
        self.directory = directory.resolve()
        self.tls_directory = tls_directory.resolve()
        self.upstream_port = upstream_port
        self.bind_host = private_host(bind_host)
        self.token = uuid.uuid4().hex
        self.container = None
        self.staging: Path | None = None
        self.name = "ripdpi-acceptance-nginx-" + self.token
        self.port = 0
        self.identity = {}

    def docker(self, *arguments: str, timeout: int = 30) -> str:
        result = subprocess.run(
            ["docker", *arguments],
            check=True,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
        return result.stdout.strip()

    def start(self) -> None:
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=False)
        for filename in ("certificate.pem", "private-key.pem"):
            path = self.tls_directory / filename
            if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077:
                raise ValueError(
                    "owned nginx TLS material must be private regular files"
                )
        self.docker("image", "inspect", NGINX_IMAGE)
        if sys.platform == "darwin":
            upstream, listen = "host.docker.internal", "8443"
            network = ["--publish", f"{self.bind_host}::8443"]
        elif sys.platform == "linux":
            with socket.socket() as reservation:
                reservation.bind((self.bind_host, 0))
                self.port = reservation.getsockname()[1]
            upstream, listen = "127.0.0.1", f"{self.bind_host}:{self.port}"
            network = ["--network", "host"]
        else:
            raise ValueError("nginx acceptance supports only Linux and macOS")
        config = self.directory / "nginx.conf"
        config.write_text(configuration(upstream, self.upstream_port, listen))
        config.chmod(0o600)
        try:
            mount_config, mount_tls = config, self.tls_directory
            if sys.platform == "darwin":
                # The local Docker VM shares the home directory, but may not share TMPDIR.
                self.staging = Path(
                    tempfile.mkdtemp(prefix=".ripdpi-nginx-", dir=Path.home())
                )
                mount_config, mount_tls = (
                    self.staging / "nginx.conf",
                    self.staging / "tls",
                )
                mount_tls.mkdir(mode=0o700)
                shutil.copyfile(config, mount_config)
                mount_config.chmod(0o600)
                for filename in ("certificate.pem", "private-key.pem"):
                    target = mount_tls / filename
                    shutil.copyfile(self.tls_directory / filename, target)
                    target.chmod(0o600)
            self.container = self.name
            self.docker(
                "run",
                "--name",
                self.name,
                "--detach",
                "--pull=never",
                "--read-only",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--user",
                f"{os.getuid()}:{os.getgid()}",
                "--label",
                f"ripdpi.acceptance.owner={self.token}",
                "--tmpfs",
                "/tmp:rw,noexec,nosuid,size=32m,mode=1777",
                "--mount",
                f"type=bind,src={mount_config},dst=/fixture/nginx.conf,readonly",
                "--mount",
                f"type=bind,src={mount_tls},dst=/fixture/tls,readonly",
                *network,
                "--entrypoint",
                "nginx",
                NGINX_IMAGE,
                "-c",
                "/fixture/nginx.conf",
                "-g",
                "daemon off;",
            )
            details = json.loads(self.docker("inspect", self.container))[0]
            if not details["State"]["Running"]:
                raise RuntimeError("owned nginx container exited before readiness")
            if sys.platform == "darwin":
                bindings = details["NetworkSettings"]["Ports"]["8443/tcp"]
                if len(bindings) != 1 or bindings[0]["HostIp"] != self.bind_host:
                    raise ValueError(
                        "nginx listener escaped the selected private address"
                    )
                self.port = int(bindings[0]["HostPort"])
            self.identity = {
                "implementation": "nginx",
                "image": NGINX_IMAGE,
                "image_id": details["Image"],
                "config_sha256": hashlib.sha256(config.read_bytes()).hexdigest(),
            }
            self.await_ready()
        except BaseException:
            self.close()
            raise

    def await_ready(self) -> None:
        context = ssl.create_default_context(
            cafile=str(self.tls_directory / "certificate.pem")
        )
        deadline = time.monotonic() + 20
        last_error = "no response"
        while time.monotonic() < deadline:
            try:
                with socket.create_connection(
                    (self.bind_host, self.port), timeout=1
                ) as connection:
                    with context.wrap_socket(
                        connection, server_hostname="fixture.test"
                    ) as tls:
                        tls.sendall(
                            b"GET / HTTP/1.1\r\nHost: fixture.test\r\nConnection: close\r\n\r\n"
                        )
                        response = tls.recv(128)
                        if response.startswith(b"HTTP/1.1 404"):
                            return
                        last_error = "unexpected HTTP readiness status"
            except (OSError, ssl.SSLError) as error:
                last_error = str(error)
            time.sleep(0.1)
        raise RuntimeError(f"owned nginx TLS readiness failed: {last_error}")

    def remove_staging(self) -> None:
        if self.staging is not None:
            shutil.rmtree(self.staging)
            self.staging = None

    def close(self) -> None:
        if self.container is None:
            self.remove_staging()
            return
        remaining = self.docker(
            "ps", "--all", "--filter", f"name=^/{self.name}$", "--format", "{{.ID}}"
        )
        if not remaining:
            self.container = None
            self.remove_staging()
            return
        details = json.loads(self.docker("inspect", self.container))[0]
        if (
            details["Config"].get("Labels", {}).get("ripdpi.acceptance.owner")
            != self.token
        ):
            raise RuntimeError("nginx container ownership mismatch")
        log = subprocess.run(
            ["docker", "logs", "--tail", "200", self.container],
            capture_output=True,
            timeout=15,
        )
        (self.directory / "nginx.log").write_bytes(log.stdout + log.stderr)
        self.docker("rm", "--force", self.container)
        remaining = self.docker(
            "ps", "--all", "--filter", f"name=^/{self.name}$", "--format", "{{.ID}}"
        )
        if remaining:
            raise RuntimeError("nginx container cleanup was not confirmed")
        self.container = None
        self.remove_staging()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare-image", action="store_true")
    parser.add_argument("--directory", type=Path)
    parser.add_argument("--tls-directory", type=Path)
    parser.add_argument("--upstream-port", type=int)
    parser.add_argument("--ready-file", type=Path)
    args = parser.parse_args()
    if args.prepare_image:
        subprocess.run(["docker", "pull", NGINX_IMAGE], check=True, timeout=180)
        return
    if any(
        value is None
        for value in (
            args.directory,
            args.tls_directory,
            args.upstream_port,
            args.ready_file,
        )
    ):
        parser.error(
            "fixture start requires --directory, --tls-directory, --upstream-port and --ready-file"
        )
    stopped = False

    def stop(_signal, _frame):
        nonlocal stopped
        stopped = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    fixture = NginxFixture(args.directory, args.tls_directory, args.upstream_port)
    try:
        fixture.start()
        with args.ready_file.open("x") as stream:
            json.dump({"port": fixture.port, "identity": fixture.identity}, stream)
        while not stopped:
            time.sleep(0.1)
    finally:
        fixture.close()


if __name__ == "__main__":
    main()
