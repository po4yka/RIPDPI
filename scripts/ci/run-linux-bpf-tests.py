#!/usr/bin/env python3
"""Build as the runner user, then run socket-filter tests with CAP_NET_ADMIN."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys


EXPECTED_TESTS = {
    "linux::tests::socket_options::bpf_drop_sack_filter_attaches_and_detaches_cleanly",
    "linux::tests::socket_options::bpf_strip_timestamps_filter_attaches_and_detaches_cleanly",
    "linux::tests::socket_options::bpf_filter_detach_removes_program",
}


SUITES = {
    "socket-options": {
        "package": "ripdpi-privileged-ops", "target": "ripdpi_privileged_ops", "kind": "lib",
        "cargo_args": ["--lib"], "tests": EXPECTED_TESTS, "name_markers": ["::bpf_"],
    },
    "network-e2e": {
        "package": "ripdpi-proxy-runtime", "target": "network_e2e", "kind": "test",
        "cargo_args": ["--test", "network_e2e"],
        "tests": {"drop_sack_and_strip_timestamps_round_trip_succeeds"},
        "name_markers": ["drop_sack", "strip_timestamps"],
    },
}


def select_binary(output: str, suite: str = "socket-options") -> Path:
    config = SUITES[suite]
    executables = set()
    for line in output.splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        target = event.get("target", {})
        if (event.get("reason") == "compiler-artifact"
                and target.get("name") == config["target"]
                and config["kind"] in target.get("kind", [])
                and event.get("profile", {}).get("test")
                and event.get("executable")):
            executables.add(event["executable"])
    if len(executables) != 1:
        raise ValueError(f"expected one {suite} test binary, found {len(executables)}")
    binary = Path(executables.pop()).resolve(strict=True)
    if not binary.is_file() or not os.access(binary, os.X_OK):
        raise ValueError(f"test binary is not executable: {binary}")
    return binary


def select_tests(listing: str, suite: str = "socket-options") -> list[str]:
    config = SUITES[suite]
    expected = config["tests"]
    tests = {line.removesuffix(": test") for line in listing.splitlines()
             if line.endswith(": test") and any(marker in line for marker in config["name_markers"])}
    if tests != expected:
        raise ValueError(f"socket-filter test inventory changed: missing={expected - tests}, extra={tests - expected}")
    return sorted(tests)


def runtime_command(binary: Path, test: str, environment: dict[str, str]) -> list[str]:
    # Build scripts and Cargo never run under sudo. setpriv drops back to the
    # runner UID and removes every other capability before executing one test.
    # Forward only the runtime profile destination after the UID drop; sudo
    # otherwise removes it, so an instrumented test would lose its coverage.
    profile = environment.get("LLVM_PROFILE_FILE")
    if environment.get("CARGO_LLVM_COV") and not profile:
        raise ValueError("instrumented socket-filter tests require LLVM_PROFILE_FILE")
    runtime_env = ["env", f"LLVM_PROFILE_FILE={profile}"] if profile else []
    return [
        "sudo", "--non-interactive", "--", "setpriv",
        "--reuid", str(os.getuid()), "--regid", str(os.getgid()), "--clear-groups",
        "--bounding-set=-all,+net_admin", "--inh-caps=-all,+net_admin",
        "--ambient-caps=-all,+net_admin", "--no-new-privs", "--",
        *runtime_env, str(binary), "--exact", test, "--nocapture",
    ]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", choices=sorted(SUITES), default="socket-options")
    args = parser.parse_args()
    config = SUITES[args.suite]
    if sys.platform != "linux":
        raise RuntimeError("socket-filter capability tests require Linux")
    if os.getuid() == 0:
        raise RuntimeError("build the test binary as an unprivileged user, not root")
    manifest = Path(__file__).resolve().parents[2] / "native/rust/Cargo.toml"
    result = subprocess.run(
        ["cargo", "test", "--locked", "--manifest-path", str(manifest),
         "-p", config["package"], *config["cargo_args"], "--no-run", "--message-format=json"],
        check=True, stdout=subprocess.PIPE, text=True,
    )
    binary = select_binary(result.stdout, args.suite)
    listing = subprocess.run([str(binary), "--list", "--format", "terse"],
                             check=True, capture_output=True, text=True)
    tests = select_tests(listing.stdout, args.suite)
    print(f"Running {len(tests)} socket-filter tests as uid {os.getuid()} with only CAP_NET_ADMIN", flush=True)
    for test in tests:
        subprocess.run(runtime_command(binary, test, os.environ), check=True)


if __name__ == "__main__":
    main()
