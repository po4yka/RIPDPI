#!/usr/bin/env python3
"""Build and exercise the production AWG runtime against an independent rootless peer.

Local invocation: build-gate -- python3 scripts/tests/run-standalone-awg-interop.py
Only the fixture binary binds host sockets, and its transport restricts them to loopback.
"""

from __future__ import annotations

import argparse
import importlib.util
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
import time


ROOT = Path(__file__).resolve().parents[2]
PEER = ROOT / "scripts/fixtures/amneziawg-peer"
RUST = ROOT / "native/rust"
WIRE_SPEC = importlib.util.spec_from_file_location("awg_wire_validation", Path(__file__).with_name("awg_wire_validation.py"))
WIRE = importlib.util.module_from_spec(WIRE_SPEC)
WIRE_SPEC.loader.exec_module(WIRE)
validate_wire_observation = WIRE.validate_wire_observation

PEERS = {
    "v3.1.20260814": ("1b86b2ae0e493e7ea93f8c1a0f0cb6735b1551f1", "h1:l2AhBD+sFycU8Im81n/bZORMxW7fWtlZJEuJ4Hh0+z0="),
    "v3.1.20260828": ("b5928efb6ca19f0153958460c3d141f04abc5c2e", "h1:D8d8gGvwXcTxUIsE4z6F6vjy4/VZddu95vMNtOygh1c="),
}
PROFILES = ("awg-3.1-ranges-v1", "amneziavpn-5x", "v3-cpa", "v3-trailers", "v3-cpa-trailers", "v3-aligned", "v3-fixed-headers")
GO_DIRECTIVE = re.compile(r"^go\s+(1\.\d+(?:\.\d+)?)\s*$", re.MULTILINE)


def peer_go_toolchain(go_mod: Path = PEER / "go.mod") -> str:
    match = GO_DIRECTIVE.search(go_mod.read_text(encoding="utf-8"))
    if match is None:
        raise ValueError(f"missing supported Go directive in {go_mod}")
    return f"go{match.group(1)}"


def checked(command: list[str], cwd: Path, env: dict[str, str], timeout: int = 600, output=None, stderr=None) -> None:
    with subprocess.Popen(command, cwd=cwd, env=env, start_new_session=True, stdout=output,
                          stderr=stderr if stderr is not None else subprocess.STDOUT if output else None) as process:
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


def verify_peer_source(version: str, metadata: dict) -> Path:
    revision, checksum = PEERS[version]
    if (metadata.get("Origin", {}).get("Hash") != revision or metadata.get("Sum") != checksum
            or metadata.get("Version") != version or not isinstance(metadata.get("Dir"), str)):
        raise ValueError(f"upstream identity mismatch for {version}")
    return Path(metadata["Dir"])


def build_peer(version: str, output: Path, env: dict[str, str]) -> Path:
    download = output / f"{version}-download.json"
    with download.open("w") as log, (output / f"{version}-download.log").open("w") as diagnostics:
        checked(["go", "mod", "download", "-json", f"github.com/amnezia-vpn/amneziawg-go/v3@{version}"],
                output, env, timeout=120, output=log, stderr=diagnostics)
    metadata = json.loads(download.read_text())
    source = verify_peer_source(version, metadata)
    fixture = output / version
    fixture.mkdir()
    for path in PEER.glob("*.go"):
        shutil.copy2(path, fixture / path.name)
    module = (PEER / "go.mod").read_text()
    module += f"\nreplace github.com/amnezia-vpn/amneziawg-go/v3 => {source}\n"
    (fixture / "go.mod").write_text(module)
    shutil.copy2(PEER / "go.sum", fixture / "go.sum")
    binary = fixture / "amneziawg-peer"
    with (fixture / "build.log").open("w") as log:
        checked(["go", "test", "-mod=readonly", "-p", "2", "./..."], fixture, env, output=log)
        checked(["go", "build", "-mod=readonly", "-p", "2", "-trimpath", "-o", str(binary), "."], fixture, env, output=log)
    (fixture / "source.json").write_text(json.dumps(metadata, indent=2) + "\n")
    return binary


def run_case(binary: Path, test_binary: Path, case: Path, env: dict[str, str], cycles: int) -> dict:
    with (case / "peer.log").open("w") as peer_log, (case / "runtime.log").open("w") as test_log:
        with subprocess.Popen([str(binary)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=peer_log, text=True, env=env, start_new_session=True) as peer:
            try:
                assert peer.stdout is not None and peer.stdin is not None
                with selectors.DefaultSelector() as selector:
                    selector.register(peer.stdout, selectors.EVENT_READ)
                    if not selector.select(timeout=15):
                        raise TimeoutError("independent peer startup deadline exceeded")
                    line = peer.stdout.readline(4096)
                startup = json.loads(line)
                if not isinstance(startup, dict) or not isinstance(startup.get("endpoint"), str):
                    raise ValueError("peer startup has no endpoint")
                endpoint = startup["endpoint"]
                host, port = endpoint.rsplit(":", 1)
                address = ipaddress.ip_address(host)
                if address.version != 4 or not address.is_loopback or not 0 < int(port) <= 65535:
                    raise ValueError("peer exposed a non-loopback or invalid endpoint")
                for cycle in range(cycles):
                    test_log.write(f"session {cycle}\n")
                    test_log.flush()
                    checked([str(test_binary), "--no-capture"], RUST,
                            dict(env, RIPDPI_AWG_INTEROP_ENDPOINT=endpoint), timeout=35, output=test_log)
                completion, _ = peer.communicate(input="stop\n", timeout=5)
                if peer.returncode != 0:
                    raise RuntimeError(f"independent peer failed: exit {peer.returncode}")
                if len(completion) > 1 << 20:
                    raise ValueError("peer observation exceeds the bounded report size")
                completed = json.loads(completion)
                if not isinstance(completed, dict) or not isinstance(completed.get("wire_observation"), dict):
                    raise ValueError("peer completion has no wire observation")
                observation = completed["wire_observation"]
                if env.get("RIPDPI_AWG_SCENARIO") == "timer-reject":
                    test_log.flush()
                    probes = [json.loads(line.removeprefix("AWG_EXPIRED_PROBE ")) for line in
                              (case / "runtime.log").read_text().splitlines() if line.startswith("AWG_EXPIRED_PROBE ")]
                    if len(probes) != 1:
                        raise ValueError("missing runtime expiry probe")
                    observation["expired_probe"] = probes[0]
                return observation
            finally:
                terminate_process_group(peer)


def run() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--peer", action="append", choices=PEERS)
    parser.add_argument("--profile", action="append", choices=PROFILES)
    parser.add_argument("--scenario", action="append", choices=("payload", "small-datagrams", *WIRE.TIMER_SCENARIOS, *WIRE.COOKIE_SCENARIOS))
    parser.add_argument("--output-dir", type=Path, default=ROOT / "build/reports/awg-standalone" / str(time.time_ns()))
    parser.add_argument("--candidate-patch", action="store_true")
    args = parser.parse_args()
    output = args.output_dir.resolve()
    status = subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=all"], cwd=ROOT, text=True)
    if status and not args.candidate_patch:
        raise ValueError("commit implementation or use --candidate-patch for local evidence")
    if any(line.startswith("??") for line in status.splitlines()):
        raise ValueError("stage new source files before candidate evidence")
    output.mkdir(parents=True, exist_ok=False)
    identity = {"source_sha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()}
    if status:
        patch = subprocess.check_output(["git", "diff", "--binary", "HEAD"], cwd=ROOT)
        (output / "candidate.patch").write_bytes(patch)
        identity["candidate_patch_sha256"] = hashlib.sha256(patch).hexdigest()
    env = dict(os.environ, GOMAXPROCS="2", GOTOOLCHAIN=peer_go_toolchain(), RIPDPI_AWG_INTEROP_CYCLES="4")
    test = ["cargo", "test", "--locked", "-p", "ripdpi-warp-core", "--features", "awg-interop",
            "--test", "standalone_awg_interop", "--jobs", "2"]
    messages = output / "cargo-artifacts.jsonl"
    with messages.open("w") as log:
        checked(test + ["--no-run", "--message-format=json"], RUST, env, output=log)
    executables = []
    for line in messages.read_text().splitlines():
        if not line.startswith("{"): continue
        message = json.loads(line)
        if (message.get("reason") == "compiler-artifact" and message.get("target", {}).get("name") == "standalone_awg_interop"
                and message.get("executable")):
            executables.append(message["executable"])
    if len(executables) != 1:
        raise ValueError("Cargo did not return one standalone AWG test executable")
    test_binary = output / "standalone-awg-test"
    shutil.copy2(executables[0], test_binary)
    identity["test_binary_sha256"] = hashlib.sha256(test_binary.read_bytes()).hexdigest()
    versions, profiles = args.peer or list(PEERS), args.profile or list(PROFILES)
    scenarios = args.scenario or ["payload", "small-datagrams", *((WIRE.TIMER_SCENARIOS + WIRE.COOKIE_SCENARIOS) if "v3-cpa-trailers" in profiles else ())]
    if any(scenario in WIRE.TIMER_SCENARIOS + WIRE.COOKIE_SCENARIOS for scenario in scenarios) and "v3-cpa-trailers" not in profiles:
        parser.error("timer scenarios require --profile v3-cpa-trailers")
    report = {"schema_version": 1, "identity": identity, "requested_peers": versions,
              "requested_profiles": profiles, "requested_scenarios": scenarios, "mtus": [1280, 1420], "cycles_by_scenario": {s: 1 if s in WIRE.TIMER_SCENARIOS + WIRE.COOKIE_SCENARIOS else 4 for s in scenarios}, "peer_builds": [], "cases": []}
    failed = False
    for version in versions:
        try:
            binary = build_peer(version, output, env)
            report["peer_builds"].append({"peer_version": version, "status": "passed"})
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            report["peer_builds"].append({"peer_version": version, "status": "failed", "error": str(error)})
            (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
            failed = True
            continue
        digest = hashlib.sha256(binary.read_bytes()).hexdigest()
        for profile in profiles:
            for scenario in scenarios:
                if scenario in WIRE.TIMER_SCENARIOS + WIRE.COOKIE_SCENARIOS and profile != "v3-cpa-trailers":
                    continue
                cycles = 1 if scenario in WIRE.TIMER_SCENARIOS + WIRE.COOKIE_SCENARIOS else 4
                for mtu in (1280, 1420):
                    case = output / f"{version}-{profile}-{scenario}-{mtu}"
                    case.mkdir()
                    result = {"peer_version": version, "peer_sha": PEERS[version][0], "peer_binary_sha256": digest,
                              "profile": profile, "scenario": scenario, "mtu": mtu, "cycles": cycles}
                    started = time.monotonic()
                    try:
                        result["observation"] = run_case(binary, test_binary, case,
                            dict(env, RIPDPI_AWG_PROFILE=profile, RIPDPI_AWG_SCENARIO=scenario, RIPDPI_AWG_INTEROP_MTU=str(mtu), RIPDPI_AWG_INTEROP_CYCLES=str(cycles)), cycles)
                        if scenario in WIRE.COOKIE_SCENARIOS:
                            WIRE.validate_cookie_observation(result["observation"], mtu, scenario)
                        elif scenario in WIRE.TIMER_SCENARIOS:
                            WIRE.validate_timer_observation(result["observation"], mtu, scenario)
                        else:
                            validate_wire_observation(result["observation"], profile, mtu, cycles, scenario)
                        result["status"] = "passed"
                    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
                        result.update(status="failed", error=str(error))
                        failed = True
                    result["elapsed_seconds"] = round(time.monotonic() - started, 3)
                    report["cases"].append(result)
                    (output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
                    print(f"{result['status']}: {version} {profile} {scenario} MTU {mtu}", flush=True)
    if failed:
        raise SystemExit("AWG matrix has failed cases; see results.json and per-case logs")


if __name__ == "__main__":
    run()
