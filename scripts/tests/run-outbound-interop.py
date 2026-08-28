#!/usr/bin/env python3
"""Exercise production outbound clients against pinned, loopback-only upstream peers.

On macOS run through build-gate. --source-dir is a clean checkout of the pinned upstream.
The peer uses that checkout's unchanged go.mod/go.sum; no production dependency.
"""
from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import shutil
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
REVISIONS = {
    "mieru": "155ebbd60f86e472586a60d7ffe58ec8f8682cb1",
    "anytls": "2012ef89768409f45437f1c06a7af5f6eea402ad",
    "ssh": "155ebbd60f86e472586a60d7ffe58ec8f8682cb1",
}
TESTS = {
    "ssh": ("password_auth_exchanges_payload_with_upstream", "encrypted_private_key_auth_exchanges_payload_with_upstream", "changed_host_key_is_rejected_before_authentication"),
    "mieru": ("tcp_stream_exchanges_payload_with_upstream", "multiplexed_tcp_streams_exchange_without_cross_contamination", "tests::backend_fixture_tests::mieru_off_socks_payload_and_stop_with_upstream"),
    "anytls": ("tcp_stream_exchanges_payload_with_upstream", "udp_datagrams_exchange_with_upstream", "upstream_rejects_wrong_password"),
}


def stop(process: subprocess.Popen) -> None:
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.killpg(process.pid, sig)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            continue


def checked(command: list[str], cwd: Path, env: dict[str, str], limit: int = 600, expected_test: str | None = None) -> None:
    with subprocess.Popen(command, cwd=cwd, env=env, start_new_session=True,
                          stdout=subprocess.PIPE if expected_test else None,
                          stderr=subprocess.STDOUT if expected_test else None, text=True) as process:
        try:
            if expected_test:
                output, _ = process.communicate(timeout=limit)
                print(output, end="", flush=True)
                code = process.returncode
                if not code and (f"test {expected_test} ... ok" not in output or "1 passed; 0 failed; 0 ignored" not in output):
                    raise RuntimeError("expected exactly one executed upstream test, not a skipped or empty run")
            else:
                code = process.wait(timeout=limit)
            if code:
                raise subprocess.CalledProcessError(code, command)
        finally:
            stop(process)


def run(arguments: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jobs", type=int, choices=(1, 2), default=1)
    parser.add_argument("--protocol", choices=REVISIONS, default="mieru")
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--test", default="tcp_stream_exchanges_payload_with_upstream")
    args = parser.parse_args(arguments)
    if args.test not in TESTS[args.protocol]:
        parser.error("unknown upstream test for selected protocol")
    source = args.source_dir.resolve()
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
    if revision != REVISIONS[args.protocol]:
        raise ValueError(f"{args.protocol} upstream revision mismatch: {revision}")
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=source, text=True).strip():
        raise ValueError("upstream checkout must be clean")
    if sys.platform == "darwin" and os.environ.get("BUILD_GATE_HELD") != "1":
        raise RuntimeError("run this compiler-backed workflow through build-gate")
    env = dict(os.environ, GOMAXPROCS=str(args.jobs), GOTOOLCHAIN="go1.27.0", GOFLAGS=f"-p={args.jobs}", GOWORK="off")
    if args.test == "changed_host_key_is_rejected_before_authentication":
        env["RIPDPI_OUTBOUND_EXPECT_NO_AUTH"] = "1"
    if args.test.startswith("tests::backend_fixture_tests::"):
        env["RIPDPI_OUTBOUND_EXPECT_IDLE"] = "1"
        test = ["cargo", "test", "--locked", "-p", "ripdpi-relay-core", "--lib", "--jobs", str(args.jobs), args.test]
    else:
        test = ["cargo", "test", "--locked", "-p", f"ripdpi-{args.protocol}", "--test", "upstream_interop", "--jobs", str(args.jobs), args.test]
    checked(test + ["--no-run"], ROOT / "native/rust", env)
    with tempfile.TemporaryDirectory(prefix="ripdpi-outbound-peer-") as directory:
        binary = Path(directory) / f"{args.protocol}-peer"
        sources = [ROOT / f"scripts/tests/outbound-{args.protocol}-oracle/main.go"]
        peer_args = [str(binary)]
        if args.protocol == "anytls":
            # Go requires named input files to share a directory. Copy the
            # original server implementations byte-for-byte, never patch them.
            sources = []
            for name in ("inbound_tcp.go", "outbound_tcp.go", "myserver.go"):
                original = source / "cmd/server" / name
                copied = Path(directory) / name
                shutil.copyfile(original, copied)
                if copied.read_bytes() != original.read_bytes():
                    raise RuntimeError("upstream source copy mismatch")
                sources.append(copied)
            main = Path(directory) / "main.go"
            shutil.copyfile(ROOT / "scripts/tests/outbound-anytls-oracle/main.go", main)
            sources.append(main)
            peer_args.append(str(Path(directory) / "certificate.pem"))
        if args.protocol == "ssh":
            peer_args.append(str(Path(directory) / "test-key.pem"))
        checked(["go", "build", "-mod=readonly", "-trimpath", "-o", str(binary), *map(str, sources)], source, env)
        print(f"Upstream {args.protocol} {revision}; peer sha256={hashlib.sha256(binary.read_bytes()).hexdigest()}", flush=True)
        with subprocess.Popen(peer_args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=None, text=True, env=env, start_new_session=True) as peer:
            try:
                assert peer.stdout is not None and peer.stdin is not None
                with selectors.DefaultSelector() as selector:
                    selector.register(peer.stdout, selectors.EVENT_READ)
                    if not selector.select(timeout=15):
                        raise TimeoutError("upstream peer startup deadline exceeded")
                    announcement = peer.stdout.readline(4096).strip()
                endpoints = json.loads(announcement)
                for key in ("endpoint", "tcp", "udp", "stats"):
                    if key in endpoints:
                        host, port = endpoints[key].rsplit(":", 1)
                        if not ipaddress.ip_address(host).is_loopback or not 0 < int(port) <= 65535:
                            raise ValueError("upstream peer must expose only loopback endpoints")
                test_env = dict(env, RIPDPI_OUTBOUND_INTEROP_ENDPOINT=endpoints["endpoint"])
                for key in ("tcp", "udp", "stats", "certificate", "fingerprint", "private_key"):
                    if key in endpoints:
                        test_env[f"RIPDPI_OUTBOUND_{key.upper()}"] = endpoints[key]
                checked(test + ["--", "--ignored", "--exact", "--nocapture"], ROOT / "native/rust", test_env, limit=35, expected_test=args.test)
                peer.stdin.write("stop\n")
                peer.stdin.flush()
                if peer.wait(timeout=5):
                    raise RuntimeError("upstream peer failed during shutdown")
            finally:
                stop(peer)


if __name__ == "__main__":
    run()
