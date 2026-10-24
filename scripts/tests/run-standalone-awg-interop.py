#!/usr/bin/env python3
"""Build and exercise the production AWG runtime against an independent rootless peer.

Local invocation: build-gate -- python3 scripts/tests/run-standalone-awg-interop.py
Only the fixture binary binds host sockets, and its transport restricts them to loopback.
"""

from __future__ import annotations

import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import selectors
import signal
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
PEER = ROOT / "scripts/fixtures/amneziawg-peer"
RUST = ROOT / "native/rust"
UPSTREAM = "amneziawg-go v3.1.20260828 (b5928efb6ca19f0153958460c3d141f04abc5c2e)"
GO_DIRECTIVE = re.compile(r"^go\s+(1\.\d+(?:\.\d+)?)\s*$", re.MULTILINE)


def peer_go_toolchain(go_mod: Path = PEER / "go.mod") -> str:
    match = GO_DIRECTIVE.search(go_mod.read_text(encoding="utf-8"))
    if match is None:
        raise ValueError(f"missing supported Go directive in {go_mod}")
    return f"go{match.group(1)}"


def checked(command: list[str], cwd: Path, env: dict[str, str], timeout: int = 600) -> None:
    with subprocess.Popen(command, cwd=cwd, env=env, start_new_session=True) as process:
        try:
            code = process.wait(timeout=timeout)
            if code != 0:
                raise subprocess.CalledProcessError(code, command)
        finally:
            # Cargo and Go may spawn compiler/test children. The private session
            # gives this invocation ownership of the entire process group.
            terminate_process_group(process)


def terminate_process_group(process: subprocess.Popen) -> None:
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        pass
    finally:
        # The leader can exit while descendants ignore TERM; kill the group
        # even when wait() has already reaped the leader.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=3)


def run() -> None:
    env = dict(os.environ, GOMAXPROCS="2", GOTOOLCHAIN=peer_go_toolchain(), RIPDPI_AWG_INTEROP_MTU="1420", RIPDPI_AWG_INTEROP_CYCLES="4")
    test = ["cargo", "test", "--locked", "-p", "ripdpi-warp-core", "--features", "awg-interop",
            "--test", "standalone_awg_interop", "--jobs", "2"]
    with tempfile.TemporaryDirectory(prefix="ripdpi-awg-peer-") as directory:
        # Read Cargo's selected executable before the peer starts. The finite
        # wire test must not wait for another build's Cargo directory lock.
        messages = Path(directory) / "cargo-artifacts.jsonl"
        with messages.open("w", encoding="utf-8") as output:
            with subprocess.Popen(test + ["--no-run", "--message-format=json"], cwd=RUST,
                                  env=env, stdout=output, start_new_session=True) as process:
                try:
                    code = process.wait(timeout=600)
                    if code != 0:
                        raise subprocess.CalledProcessError(code, test)
                finally:
                    terminate_process_group(process)
        executables = []
        for line in messages.read_text(encoding="utf-8").splitlines():
            message = json.loads(line)
            if (message.get("reason") == "compiler-artifact"
                    and message.get("target", {}).get("name") == "standalone_awg_interop"
                    and message.get("executable")):
                executables.append(message["executable"])
        if len(executables) != 1:
            raise ValueError("Cargo did not return one standalone AWG test executable")
        test_binary = Path(directory) / "standalone-awg-test"
        shutil.copy2(executables[0], test_binary)
        binary = Path(directory) / "amneziawg-peer"
        checked(["go", "build", "-mod=readonly", "-p", "2", "-trimpath", "-o", str(binary), "."], PEER, env)
        print(f"Independent peer: {UPSTREAM}; sha256={hashlib.sha256(binary.read_bytes()).hexdigest()}", flush=True)
        for mtu in (1280, 1420):
            env["RIPDPI_AWG_INTEROP_MTU"] = str(mtu)
            with subprocess.Popen([str(binary)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=None, text=True, env=env) as peer:
                try:
                    assert peer.stdout is not None and peer.stdin is not None
                    with selectors.DefaultSelector() as selector:
                        selector.register(peer.stdout, selectors.EVENT_READ)
                        if not selector.select(timeout=15):
                            raise TimeoutError("independent peer startup deadline exceeded")
                        line = peer.stdout.readline(4096)
                    endpoint = json.loads(line)["endpoint"]
                    host, port = endpoint.rsplit(":", 1)
                    address = ipaddress.ip_address(host)
                    if address.version != 4 or not address.is_loopback or not 0 < int(port) <= 65535:
                        raise ValueError("peer exposed a non-loopback or invalid endpoint")
                    for cycle in range(4):
                        print(f"MTU {mtu}, session {cycle}", flush=True)
                        checked([str(test_binary), "--nocapture"], RUST,
                                dict(env, RIPDPI_AWG_INTEROP_ENDPOINT=endpoint), timeout=35)
                    peer.stdin.write("stop\n")
                    peer.stdin.flush()
                    code = peer.wait(timeout=5)
                    if code != 0:
                        raise RuntimeError(f"independent peer failed: exit {code}")
                    observation = json.loads(peer.stdout.readline(4096))["wire_observation"]
                    print(json.dumps(observation, sort_keys=True), flush=True)
                    print("PASS: real AWG handshake, IPv4/IPv6 TCP and UDP sources, stalled-client shutdown", flush=True)
                finally:
                    if peer.poll() is None:
                        peer.terminate()
                        try:
                            peer.wait(timeout=3)
                        except subprocess.TimeoutExpired:
                            peer.kill()
                            peer.wait(timeout=3)


if __name__ == "__main__":
    run()
