#!/usr/bin/env python3
"""Run the real native client through the owned pinned Hysteria DNS fixture.

Run through build-gate. Uses loopback only; no device or provider access.
"""

from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import tempfile
import time
import urllib.request
from pathlib import Path

from android_hysteria_fixture import HysteriaFixture, verify_binary

ROOT = Path(__file__).resolve().parents[2]
TEST = "android_fixture_routes_synthetic_dns_and_rejects_wrong_pin_and_destination"


def fetch(port: int, path: str) -> dict:
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(f"http://127.0.0.1:{port}{path}", timeout=3) as response:
        return json.load(response)


def run(binary: Path, go: str) -> None:
    verify_binary(binary)
    with tempfile.TemporaryDirectory(prefix="owned-hysteria-dns-") as temporary:
        directory = Path(temporary)
        peer_binary = directory / "peer"
        subprocess.run(
            [go, "build", "-p", "4", "-o", str(peer_binary), "."],
            cwd=ROOT / "scripts/fixtures/xray-provider-peer",
            check=True,
            timeout=180,
        )
        ready = directory / "ready.json"
        tls = directory / "tls"
        with (directory / "peer.log").open("w") as log:
            peer = subprocess.Popen(
                [
                    str(peer_binary),
                    "--ready-file",
                    str(ready),
                    "--tls-directory",
                    str(tls),
                    "--advertise-host",
                    "127.0.0.1",
                ],
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
            fixture = None
            try:
                deadline = time.monotonic() + 15
                while True:
                    try:
                        control = json.loads(ready.read_text())["controlPort"]
                        break
                    except (FileNotFoundError, json.JSONDecodeError):
                        if peer.poll() is not None or time.monotonic() >= deadline:
                            raise RuntimeError("owned Xray peer readiness failed")
                        time.sleep(0.05)
                manifest = fetch(control, "/manifest")
                fixture = HysteriaFixture(
                    directory / "hysteria",
                    tls,
                    binary,
                    "127.0.0.1",
                    "127.0.0.1",
                    manifest,
                    control,
                )
                fixture.start()
                environment = dict(
                    os.environ,
                    RIPDPI_HYSTERIA_ENDPOINT=f"127.0.0.1:{fixture.gate.port}",
                    RIPDPI_HYSTERIA_AUTH=fixture.password,
                    RIPDPI_HYSTERIA_PIN=fixture.manifest["hysteriaCertificatePin"],
                    RIPDPI_HYSTERIA_OBFS=fixture.salamander,
                    RIPDPI_HYSTERIA_DNS_PORT=str(manifest["dnsPort"]),
                )
                result = subprocess.run(
                    [
                        "cargo",
                        "test",
                        "--locked",
                        "--jobs",
                        "4",
                        "-p",
                        "ripdpi-hysteria2",
                        "--test",
                        "upstream_interop",
                        TEST,
                        "--",
                        "--exact",
                        "--ignored",
                    ],
                    cwd=ROOT / "native/rust",
                    env=environment,
                    text=True,
                    stdout=subprocess.PIPE,
                    check=False,
                    stderr=subprocess.STDOUT,
                    timeout=180,
                )
                print(result.stdout, end="")
                if (
                    result.returncode
                    or f"test {TEST} ... ok" not in result.stdout
                    or "1 passed; 0 failed; 0 ignored;" not in result.stdout
                ):
                    raise RuntimeError(
                        "native DNS fixture test failed or did not execute"
                    )
                receipts = fetch(control, "/dns-receipts")
                if receipts != {"count": 2, "lastQuery": "owned.test."}:
                    raise RuntimeError("owned DNS receipt count or query mismatch")
            finally:
                try:
                    if fixture is not None:
                        fixture.close()
                finally:
                    if peer.poll() is None:
                        os.killpg(peer.pid, signal.SIGTERM)
                        try:
                            peer.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            os.killpg(peer.pid, signal.SIGKILL)
                            peer.wait(timeout=5)
            gate = json.loads((directory / "hysteria/udp-receipts.json").read_text())
            if (
                gate["errorCategory"] is not None
                or gate["workerAlive"]
                or not gate["socketsClosed"]
            ):
                raise RuntimeError("owned UDP gate cleanup failed")
            print(
                "PASS: native pinned Hysteria DNS, wrong pin, wrong destinations, exact receipts, cleanup"
            )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hysteria-binary", required=True, type=Path)
    parser.add_argument("--go", default="go")
    args = parser.parse_args()
    if os.environ.get("BUILD_GATE_HELD") != "1":
        parser.error("run this check through build-gate")
    run(args.hysteria_binary.absolute(), args.go)
