"""Pinned Caddy + klzgrad/forwardproxy NaiveProxy server with destination receipts.

Preparation builds the server the way the fork's release workflow does: the
pinned Caddy tag with the fork's two patches, plus the pinned forwardproxy
module. Runs use the prepared binary only after its recorded digest matches.
"""

from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time

import go_toolchain
import hysteria

FORWARDPROXY = (
    "https://github.com/klzgrad/forwardproxy.git",
    "e747854e78743dba85c039b38ed185fb11d93667",  # tag v2.11.7-naive2
)
CADDY = (
    "https://github.com/caddyserver/caddy.git",
    "v2.11.7",
    "72dd0fb067f6d7826c7f79907670ba4a713bfe37",
)
# sha256 of the patches in the pinned forwardproxy checkout, applied by its build.yml.
PATCHES = {
    "caddy-sing-quic-bbrv1.patch": "298a057cf1640718a14157bcd22a37575538adffba5189e7a7ecd54bffe7cb8e",
    "caddy-read-idle-timeout.patch": "02583f364374e9461cee9b954349b87e0390d600319beafcf52ebcc5e2947eab",
}
GO_VERSION = go_toolchain.OUTBOUND_VERSION
SERVER_NAME = "naive.test"
MAIN_GO = """package main

import (
\tcaddycmd "github.com/caddyserver/caddy/v2/cmd"
\t_ "github.com/caddyserver/caddy/v2/modules/standard"
\t_ "github.com/caddyserver/forwardproxy"
)

func main() { caddycmd.Main() }
"""


def identity():
    return {
        "implementation": "klzgrad/forwardproxy on Caddy",
        "repository": FORWARDPROXY[0],
        "revision": FORWARDPROXY[1],
        "caddy_version": CADDY[1],
        "caddy_revision": CADDY[2],
        "patches": dict(PATCHES),
        "go_version": GO_VERSION,
    }


def root(cache):
    return cache / f"naiveproxy-forwardproxy-{FORWARDPROXY[1][:12]}"


def checkout(directory, repository, ref, revision):
    if not directory.exists():
        directory.mkdir(parents=True)
        for command in (
            ["git", "init", "--quiet"],
            ["git", "fetch", "--quiet", "--depth=1", repository, ref],
            ["git", "checkout", "--quiet", "--detach", "FETCH_HEAD"],
        ):
            subprocess.run(command, cwd=directory, check=True, timeout=300)
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=directory, text=True).strip()
    if head != revision:
        raise ValueError(f"{repository} checkout is {head}, not the pinned {revision}")


def prepare(cache):
    """Fetch, patch, and build the server while preparation is online."""
    base = root(cache)
    forwardproxy, caddy, module = base / "forwardproxy", base / "caddy", base / "build"
    checkout(forwardproxy, FORWARDPROXY[0], FORWARDPROXY[1], FORWARDPROXY[1])
    if (forwardproxy / "CADDY_VERSION").read_text().strip() != CADDY[1]:
        raise ValueError("forwardproxy CADDY_VERSION differs from the pinned Caddy tag")
    fresh_caddy = not caddy.exists()
    checkout(caddy, CADDY[0], f"refs/tags/{CADDY[1]}", CADDY[2])
    for name, expected in PATCHES.items():
        if go_toolchain.digest(forwardproxy / name) != expected:
            raise ValueError(f"forwardproxy patch digest mismatch: {name}")
        if fresh_caddy:
            subprocess.run(["git", "apply", str(forwardproxy / name)], cwd=caddy, check=True, timeout=60)
    toolchain = go_toolchain.prepare(cache, GO_VERSION, forwardproxy)
    module.mkdir(exist_ok=True)
    (module / "main.go").write_text(MAIN_GO)
    (module / "go.mod").write_text(
        "module ripdpi.test/naiveproxy-peer\n\ngo 1.26\n\n"
        f"require (\n\tgithub.com/caddyserver/caddy/v2 {CADDY[1]}\n"
        "\tgithub.com/caddyserver/forwardproxy v0.0.0\n)\n\n"
        "replace github.com/caddyserver/caddy/v2 => ../caddy\n"
        "replace github.com/caddyserver/forwardproxy => ../forwardproxy\n"
    )
    # Preparation is the online step: the runtime environment's GOPROXY=off is lifted here only.
    env = {
        **os.environ,
        **go_toolchain.environment(Path(toolchain["root"])),
        "GOPROXY": "https://proxy.golang.org",
        "GOSUMDB": "sum.golang.org",
        "CGO_ENABLED": "0",
    }
    subprocess.run(["go", "mod", "tidy"], cwd=module, env=env, check=True, timeout=900)
    output = base / "caddy-forwardproxy-naive"
    subprocess.run(
        ["go", "build", "-trimpath", "-o", str(output), "."],
        cwd=module,
        env=env,
        check=True,
        timeout=1800,
    )
    metadata = dict(identity(), sha256=go_toolchain.digest(output))
    (base / "peer.json").write_text(json.dumps(metadata, indent=2) + "\n")
    return metadata


def binary(cache):
    base = root(cache)
    path, record = base / "caddy-forwardproxy-naive", base / "peer.json"
    if not path.is_file() or not record.is_file():
        raise FileNotFoundError("NaiveProxy server peer is not prepared; run the scenario with --prepare")
    metadata = json.loads(record.read_text())
    if {key: metadata.get(key) for key in identity()} != identity():
        raise ValueError("prepared NaiveProxy server does not match the pinned sources")
    if go_toolchain.digest(path) != metadata["sha256"]:
        raise ValueError("prepared NaiveProxy server digest mismatch")
    return path, metadata["sha256"]


def certificates(directory):
    """A private test CA and a `naive.test` leaf it signs; the CA goes to the client as DER."""
    def openssl(*args):
        subprocess.run(["openssl", *args], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)

    ca_key, ca_cert, ca_der = directory / "ca.key", directory / "ca.pem", directory / "ca.der"
    key, request, cert = directory / "leaf.key", directory / "leaf.csr", directory / "leaf.pem"
    extensions = directory / "leaf.ext"
    extensions.write_text(
        "basicConstraints=critical,CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\n"
        f"extendedKeyUsage=serverAuth\nsubjectAltName=DNS:{SERVER_NAME}\n"
    )
    openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=ripdpi naive test CA",
            "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign",
            "-keyout", str(ca_key), "-out", str(ca_cert))
    openssl("req", "-newkey", "rsa:2048", "-nodes", "-subj", f"/CN={SERVER_NAME}", "-keyout", str(key), "-out", str(request))
    openssl("x509", "-req", "-in", str(request), "-CA", str(ca_cert), "-CAkey", str(ca_key), "-CAcreateserial",
            "-days", "1", "-extfile", str(extensions), "-out", str(cert))
    openssl("x509", "-in", str(ca_cert), "-outform", "der", "-out", str(ca_der))
    return cert, key, ca_der


def free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as reservation:
        reservation.bind(("127.0.0.1", 0))
        return reservation.getsockname()[1]


@contextmanager
def peer(cache, out_dir, run_id, cleanup):
    executable, digest = binary(cache)
    payload = f"ripdpi-acceptance:{run_id}".encode()
    destinations = hysteria.Destinations(payload)
    closed_port = free_port()
    cleanup["passed"] = False
    try:
        with tempfile.TemporaryDirectory(prefix="ripdpi-naive-peer-") as directory:
            private = Path(directory)
            cert, key, ca_der = certificates(private)
            port = free_port()
            user, password = "ripdpi", secrets.token_hex(24)
            config = private / "Caddyfile"
            config.write_text(
                "{\n\tadmin off\n\tauto_https off\n}\n"
                f":{port} {{\n\tbind 127.0.0.1\n\ttls {cert} {key}\n\troute {{\n"
                f"\t\tforward_proxy {{\n\t\t\tbasic_auth {user} {password}\n\t\t\thide_ip\n\t\t\thide_via\n"
                "\t\t\tacl {\n\t\t\t\tallow 127.0.0.1\n\t\t\t\tallow localhost\n\t\t\t}\n\t\t}\n"
                "\t\trespond \"ripdpi cover site\" 200\n\t}\n}\n"
            )
            config.chmod(0o600)
            log_path = out_dir / "peer.log"
            with log_path.open("w") as log, subprocess.Popen(
                [str(executable), "run", "--config", str(config), "--adapter", "caddyfile"],
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
                env=dict(os.environ, HOME=directory, XDG_DATA_HOME=directory, XDG_CONFIG_HOME=directory),
            ) as process:
                try:
                    deadline = time.monotonic() + 15
                    while True:
                        if process.poll() is not None:
                            raise RuntimeError("NaiveProxy server exited before readiness; see private peer.log")
                        try:
                            socket.create_connection(("127.0.0.1", port), timeout=0.2).close()
                            break
                        except OSError:
                            if time.monotonic() >= deadline:
                                raise TimeoutError("NaiveProxy server readiness deadline exceeded")
                            time.sleep(0.05)
                    print(f"Independent peer: klzgrad/forwardproxy {FORWARDPROXY[1]}; sha256={digest}", flush=True)
                    yield {
                        "env": {
                            "RIPDPI_NAIVE_ENDPOINT": f"127.0.0.1:{port}",
                            "RIPDPI_NAIVE_SERVER_NAME": SERVER_NAME,
                            "RIPDPI_NAIVE_CA_DER": str(ca_der),
                            "RIPDPI_NAIVE_USER": user,
                            "RIPDPI_NAIVE_PASSWORD": password,
                            "RIPDPI_NAIVE_TCP": f"127.0.0.1:{destinations.tcp.getsockname()[1]}",
                            "RIPDPI_NAIVE_CLOSED": f"127.0.0.1:{closed_port}",
                            "RIPDPI_NAIVE_PAYLOAD": payload.decode(),
                        },
                        "receipts": destinations.receipts,
                        "errors": destinations.errors,
                        "sha256": digest,
                        "payload_sha256": hashlib.sha256(payload).hexdigest(),
                    }
                finally:
                    hysteria.stop_group(process)
    finally:
        destinations.close()
        cleanup["passed"] = True
