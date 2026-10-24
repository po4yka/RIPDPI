#!/usr/bin/env python3
"""Run exact Android TUN acceptance methods against owned local peers.

Select a leased disposable emulator or an explicitly authorized physical device.
Physical runs require fresh acceptance-owned app installations and a reachable
private LAN fixture. Remote fixtures belong to their controller; host fixtures
and control forwards are scoped to this run.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import secrets
import uuid
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_nginx_fixture import NginxFixture
from android_owned_installation import OwnedInstallation
from android_hysteria_fixture import HysteriaFixture, verify_binary

ROOT = Path(__file__).resolve().parents[2]
PREFIX = "com.poyka.ripdpi.e2e."
SCENARIOS = {
    "android-awg": [
        PREFIX
        + "AmneziaWgTransportE2ETest#savedRangeProfileRoutesDnsDuplexStreamAndReconnectsThroughTun"
    ],
    "android-migration": [
        PREFIX
        + "MigrationTransportE2ETest#savedReserveAndIndependentRefreshProduceTestedMigrationReceipt"
    ],
    "android-hysteria": [
        PREFIX
        + "XrayProviderE2ETest#hysteriaCompletesTransfersSleepWakeAndUdpFailureFallsBackToSavedTcp"
    ],
    "android-transactions": [
        PREFIX
        + "XrayProviderE2ETest#nginxTlsAndRealityCompleteBoundedTransfersAndRecover"
    ],
    "android-xray": [
        PREFIX
        + "XrayProviderE2ETest#bothTransportsRouteDistinctUidTrafficThroughRealTunAndRestart",
        PREFIX + "XrayProviderE2ETest#wrongIdentityCannotReachPeerOrFallBackToDirect",
        PREFIX
        + "XrayProviderE2ETest#peerLossAndRecoveryPreservesTunAndRejectsDirectBypass",
    ],
    "android-network": [
        PREFIX
        + "EnvironmentPreflightE2ETest#environmentSupportsFixtureReachabilityAndVpnConsent",
        PREFIX + "NetworkPathE2ETest#localAcceptanceTunTcpUdpFaultAndRecovery",
        PREFIX
        + "NetworkPathE2ETest#vpnServiceRoutesHostnameTrafficThroughEncryptedDnsWithoutRestartLoop",
        PREFIX
        + "NetworkPathE2ETest#vpnServiceEncryptedDnsFaultBreaksHostnameShellRoundTrip",
        PREFIX
        + "NetworkPathE2ETest#vpnServiceSurfacedFixtureFaultBreaksShellRoundTrip",
    ],
}
for transport in ("transactions", "hysteria", "awg", "migration"):
    original = SCENARIOS[f"android-{transport}"][0]
    test_class, method = original.split("#")
    SCENARIOS[f"android-usb-{transport}"] = [
        f"{test_class}#usb{method[0].upper()}{method[1:]}"
    ]

PACKAGE = "com.poyka.ripdpi"
TOKEN = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}\Z")


class Blocked(RuntimeError):
    """A required runtime prerequisite is absent."""


def owned_instrumentation_component(components: str) -> str:
    matches = re.findall(
        r"^instrumentation:("
        + re.escape(PACKAGE + ".test")
        + r"/[A-Za-z0-9_.]+) \(target="
        + re.escape(PACKAGE)
        + r"\)\s*$",
        components,
        re.M,
    )
    if len(matches) != 1:
        raise Blocked("expected exactly one installed RIPDPI test runner")
    return matches[0]


def heavy_command(command: list[str]) -> list[str]:
    # macOS uses the machine-wide memory gate. Hosted Linux jobs have isolated runners.
    return (["build-gate", "--"] if sys.platform == "darwin" else []) + command


def awg_peer_environment(host: str | None, peer: str | None) -> dict[str, str]:
    env = os.environ.copy()
    env.update(
        RIPDPI_AWG_HTTP_ACCEPTANCE="1",
        RIPDPI_AWG_INTEROP_MTU="1330",
        RIPDPI_AWG_INTEROP_CYCLES="1",
    )
    env.pop("RIPDPI_AWG_BIND_IP", None)
    env.pop("RIPDPI_AWG_PEER_IP", None)
    if host:
        env.update(RIPDPI_AWG_BIND_IP=host, RIPDPI_AWG_PEER_IP=peer)
    return env


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def source_fingerprint() -> str:
    """Bind reuse to tracked changes and new source files, not HEAD alone."""
    checksum = hashlib.sha256()
    checksum.update(
        subprocess.check_output(["git", "diff", "--binary", "HEAD"], cwd=ROOT)
    )
    untracked = (
        subprocess.check_output(
            ["git", "ls-files", "--others", "--exclude-standard", "-z"], cwd=ROOT
        )
        .decode()
        .split("\0")
    )
    for name in sorted(filter(None, untracked)):
        path = ROOT / name
        checksum.update(name.encode())
        if path.is_file():
            checksum.update(digest(path).encode())
    return checksum.hexdigest()


def local_host(value: str) -> str:
    try:
        address = ipaddress.IPv4Address(value)
    except ipaddress.AddressValueError as error:
        raise argparse.ArgumentTypeError(
            "use a numeric private IPv4 address"
        ) from error
    private = any(
        address in subnet
        for subnet in (
            ipaddress.IPv4Network("10.0.0.0/8"),
            ipaddress.IPv4Network("172.16.0.0/12"),
            ipaddress.IPv4Network("192.168.0.0/16"),
            ipaddress.IPv4Network("127.0.0.0/8"),
        )
    )
    if not private:
        raise argparse.ArgumentTypeError(
            "only loopback or RFC1918 fixture addresses are allowed"
        )
    return value


def parse_instrumentation(output: str, expected: str) -> None:
    """Require one start and success for the exact method, and runner completion.

    adb returns zero for many instrumentation failures. Status -3/-4 indicates
    ignored/assumption-skipped tests and must never count as acceptance.
    """
    fields: dict[str, str] = {}
    started: list[str] = []
    completed: list[str] = []
    final_codes: list[int] = []
    for raw in output.splitlines():
        line = raw.rstrip("\r")
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition(
                "="
            )
            if sep:
                fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(":", 1)[1])
            identity = fields.get("class", "") + "#" + fields.get("test", "")
            if code == 1:
                started.append(identity)
            elif code == 0:
                completed.append(identity)
            elif code != 2:
                raise ValueError(f"instrumentation failed or skipped: status {code}")
            fields = {}
        elif line.startswith("INSTRUMENTATION_CODE: "):
            final_codes.append(int(line.split(":", 1)[1]))
        elif line.startswith(
            (
                "INSTRUMENTATION_FAILED:",
                "INSTRUMENTATION_ABORTED:",
                "INSTRUMENTATION_RESULT: shortMsg=",
            )
        ):
            raise ValueError("instrumentation did not complete")
    if started != [expected] or completed != [expected] or final_codes != [-1]:
        raise ValueError(
            "missing, duplicate, unexpected or incomplete instrumentation evidence"
        )


def write_junit(path: Path, expected: str) -> None:
    suite = ET.Element(
        "testsuite",
        name="android-local-acceptance",
        tests="1",
        failures="0",
        errors="0",
        skipped="0",
    )
    class_name, method = expected.split("#")
    ET.SubElement(suite, "testcase", classname=class_name, name=method)
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def fetch(host: str, port: int, endpoint: str, method: str = "GET") -> object:
    # Never use ambient HTTP proxies for local fixture control.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(
        urllib.request.Request(f"http://{host}:{port}/{endpoint}", method=method),
        timeout=5,
    ) as response:
        return json.load(response)


def migration_payloads(
    old: dict,
    reserve: dict,
    replacement: dict,
    host: str,
    tls_port: int,
    mirror_token: str,
    identity: dict,
) -> tuple[bytes, bytes]:
    def outbound(tag: str, peer: dict) -> dict:
        return {
            "type": "vless",
            "tag": tag,
            "server": host,
            "server_port": peer["tcpPort"],
            "uuid": peer["uuid"],
            "flow": "xtls-rprx-vision",
            "tls": {
                "enabled": True,
                "server_name": "fixture.test",
                "utls": {"enabled": True, "fingerprint": "chrome"},
                "reality": {
                    "enabled": True,
                    "public_key": peer["publicKey"],
                    "short_id": "ab12",
                },
            },
        }

    bundle = {
        "outbounds": [
            {
                "type": "selector",
                "tag": "select",
                "outbounds": ["old", "reserve"],
                "default": "old",
            },
            outbound("old", old),
            outbound("reserve", reserve),
        ],
        "ripdpi": {
            "schema_version": 2,
            "subscription_mirrors": [
                {
                    "id": "independent",
                    "transport": "direct",
                    "url": f"https://fixture.test:{tls_port}/mirror",
                    "token": mirror_token,
                }
            ],
        },
    }
    initial = json.dumps(bundle, sort_keys=True, separators=(",", ":")).encode()
    bundle["outbounds"] = [
        {
            "type": "selector",
            "tag": "select",
            "outbounds": ["reserve", "replacement"],
            "default": "reserve",
        },
        outbound("reserve", reserve),
        outbound("replacement", replacement),
    ]
    bundle["ripdpi"]["migration"] = {
        "schema_version": 1,
        "migration_id": identity["migration_id"],
        "delivery_id": identity["delivery_id"],
        "receipt_key": identity["receipt_key"],
        "target_tags": ["replacement"],
        "awg_previous_tags": {},
    }
    updated = json.dumps(bundle, sort_keys=True, separators=(",", ":")).encode()
    identity["payload_sha256"] = hashlib.sha256(updated).hexdigest()
    return initial, updated


def validate_migration_receipt(receipt: dict, identity: dict) -> None:
    for key in ("migration_id", "delivery_id", "payload_sha256"):
        if receipt.get(key) != identity[key]:
            raise ValueError(
                "migration receipt identity differs from the delivered payload"
            )
    if (
        receipt.get("schema_version") != 1
        or receipt.get("state") != "path-tested"
        or receipt.get("tested_tag") != "replacement"
    ):
        raise ValueError(
            "migration receipt does not prove the selected replacement path"
        )
    received, tested = receipt.get("received_at_ms"), receipt.get("tested_at_ms")
    if (
        type(received) is not int
        or type(tested) is not int
        or not 0 < received <= tested
    ):
        raise ValueError("migration receipt timestamp order is invalid")
    message = "\n".join(
        [
            "ripdpi-device-migration-receipt-v1",
            identity["migration_id"],
            identity["delivery_id"],
            identity["payload_sha256"],
            "path-tested",
            str(received),
            str(tested),
            "replacement",
            "",
        ]
    )
    expected = hmac.new(
        bytes.fromhex(identity["receipt_key"]), message.encode(), hashlib.sha256
    ).hexdigest()
    if not hmac.compare_digest(str(receipt.get("mac", "")), expected):
        raise ValueError("migration receipt authentication failed")


def group_has_live_process(group_id: int) -> bool:
    # A dead leader can leave descendants in its process group. Do not confuse
    # an unreaped zombie with a process that can still send traffic.
    rows = subprocess.check_output(["ps", "-axo", "pgid=,stat="], text=True)
    for row in rows.splitlines():
        fields = row.split()
        if (
            len(fields) >= 2
            and fields[0] == str(group_id)
            and not fields[1].startswith("Z")
        ):
            return True
    return False


def stop_group(process: subprocess.Popen) -> None:
    for signum, timeout in ((signal.SIGTERM, 5), (signal.SIGKILL, 5)):
        try:
            os.killpg(process.pid, signum)
        except ProcessLookupError:
            pass
        except PermissionError:
            if group_has_live_process(process.pid):
                raise
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            process.poll()
            if not group_has_live_process(process.pid):
                process.wait(timeout=1)
                return
            time.sleep(0.05)
    raise RuntimeError("owned process group did not stop")


def run(
    command: list[str],
    log: Path,
    *,
    cwd: Path = ROOT,
    env: dict | None = None,
    timeout: int = 600,
) -> str:
    with log.open("w") as stream:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            env=env,
            stdout=stream,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            status = process.wait(timeout=timeout)
        finally:
            stop_group(process)
    output = log.read_text(errors="replace")
    if status != 0:
        raise RuntimeError(f"command failed ({status}); see {log.name}")
    return output


class OwnedProcess:
    def __init__(self, command: list[str], log: Path, *, env: dict | None = None):
        self.stream = log.open("w")
        try:
            self.process = subprocess.Popen(
                command,
                cwd=ROOT,
                env=env,
                stdout=self.stream,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
        except BaseException:
            self.stream.close()
            raise
        self.closed = False

    def close(self) -> None:
        if self.closed:
            return
        try:
            stop_group(self.process)
        finally:
            self.stream.close()
        self.closed = True


def wait_json(path: Path, process: OwnedProcess, timeout: int = 60) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.process.poll() is not None:
            raise RuntimeError("owned fixture exited before readiness")
        if path.exists():
            for line in path.read_text(errors="replace").splitlines():
                try:
                    value = json.loads(line)
                    if isinstance(value, dict):
                        return value
                except json.JSONDecodeError:
                    pass
        time.sleep(0.1)
    raise RuntimeError("owned fixture readiness timed out")


def start_migration_peer(
    binary: Path,
    out: Path,
    run_id: str,
    name: str,
    owned: list[OwnedProcess],
    host: str | None = None,
) -> dict:
    ready_path = out / f"{name}-ready.json"
    client_id = str(uuid.uuid4())
    child = OwnedProcess(
        [
            str(binary),
            "--ready-file",
            str(ready_path),
            "--run-id",
            run_id,
            "--client-id",
            client_id,
        ]
        + (["--bind-host", host, "--advertise-host", host] if host else []),
        out / f"{name}-peer.log",
    )
    owned.append(child)
    ready = wait_json(ready_path, child)
    manifest = fetch("127.0.0.1", ready["controlPort"], "manifest")
    if manifest.get("runId") != run_id or manifest.get("uuid") != client_id:
        raise RuntimeError("migration peer identity differs from its owned process")
    manifest["controlPort"] = ready["controlPort"]
    return manifest


def prepare(
    out: Path, abi: str, source: str, xray_artifact_dir: Path | None = None
) -> dict:
    xray_artifact_dir = xray_artifact_dir or ROOT / "native/xray/artifacts"
    if not (xray_artifact_dir / "libxray.aar").is_file():
        raise Blocked(
            "real libXray AAR is absent; prepare it with scripts/native/build-libxray.sh"
        )
    verifier_env = os.environ.copy()
    verifier_env["RIPDPI_XRAY_AAR_DIR"] = str(xray_artifact_dir.resolve())
    try:
        run(
            [
                "bash",
                str(ROOT / "scripts/native/verify-libxray-artifacts.sh"),
                "--abis",
                abi,
            ],
            out / "xray-preflight.log",
            env=verifier_env,
        )
    except RuntimeError as error:
        raise Blocked(
            "libXray prerequisite verification failed; see xray-preflight.log"
        ) from error
    source_tree = source_fingerprint()
    run(
        heavy_command(
            [
                "env",
                "-u",
                "CARGO_BUILD_JOBS",
                str(ROOT / "gradlew"),
                "--max-workers=4",
                "-Pripdpi.nativeCpuBudget=4",
                ":app:assembleGithubFullDebug",
                ":app:assembleGithubFullDebugAndroidTest",
                f"-Pripdpi.nativeAbisOverride={abi}",
                "-Pripdpi.enableAbiSplits=false",
            ]
            + (
                [f"-Pripdpi.prebuiltXrayAarDir={xray_artifact_dir.resolve()}"]
                if xray_artifact_dir
                else []
            )
        ),
        out / "build.log",
        timeout=7200,
    )
    if (
        source_fingerprint() != source_tree
        or subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT)
        .decode()
        .strip()
        != source
    ):
        raise RuntimeError("source changed during APK preparation")
    paths = {
        "app": ROOT
        / "app/build/outputs/apk/githubFull/debug/app-github-full-debug.apk",
        "test": ROOT
        / "app/build/outputs/apk/androidTest/githubFull/debug/app-github-full-debug-androidTest.apk",
    }
    metadata = {
        "source_sha": source,
        "source_tree_sha256": source_tree,
        "abi": abi,
        "apks": {},
    }
    for kind, path in paths.items():
        if not path.is_file():
            raise RuntimeError(f"build did not produce required {kind} APK")
        metadata["apks"][kind] = {"path": str(path), "sha256": digest(path)}
    (out / "apk-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    return metadata


def prepared(path: Path, abi: str, source: str) -> dict:
    metadata = json.loads((path / "apk-metadata.json").read_text())
    if (
        metadata.get("source_sha") != source
        or metadata.get("abi") != abi
        or metadata.get("source_tree_sha256") != source_fingerprint()
    ):
        raise ValueError("prepared APK source or ABI differs from this run")
    for kind in ("app", "test"):
        item = metadata["apks"][kind]
        if digest(Path(item["path"])) != item["sha256"]:
            raise ValueError("prepared APK content changed")
    return metadata


def preparation_summary(out: Path, report: dict) -> dict:
    """Export fixed diagnostic codes, never raw command output or exception text."""
    summary = {
        "schema_version": 1,
        "status": report["status"],
        "outputs_present": [],
        "diagnostic_codes": [],
    }
    for filename, step in (
        ("source.log", "source"),
        ("devices.log", "device-list"),
        ("device-kind.log", "device-kind"),
        ("device-abi.log", "device-abi"),
        ("xray-preflight.log", "xray-preflight"),
        ("build.log", "apk-build"),
        ("apk-metadata.json", "apk-metadata"),
    ):
        if (out / filename).is_file():
            summary["outputs_present"].append(step)
    build_log = out / "build.log"
    if build_log.is_file():
        with build_log.open("rb") as stream:
            stream.seek(max(0, build_log.stat().st_size - 256 * 1024))
            output = stream.read().decode(errors="replace")
        signatures = {
            "disk-full": r"No space left on device",
            "jvm-out-of-memory": r"OutOfMemoryError|Java heap space|GC overhead limit",
            "gradle-daemon-lost": r"daemon (?:has )?disappeared|daemon disappeared",
            "kotlin-compile": r"> Task :\S*compile\S*Kotlin FAILED|Compilation error",
            "rust-compile": r"could not compile |error\[E\d+\]",
            "ambient-cargo-override": r"Android native builds reject output-affecting ambient Cargo overrides",
            "dependency-resolution": r"Could not resolve |Could not download ",
            "native-link": r"linking with .* failed|ld: error:|ld.lld: error:",
            "build-successful": r"BUILD SUCCESSFUL",
        }
        summary["diagnostic_codes"] = [
            code for code, pattern in signatures.items() if re.search(pattern, output)
        ]
    post_build_errors = {
        "source changed during APK preparation": "source-changed",
        "build did not produce required app APK": "app-apk-missing",
        "build did not produce required test APK": "test-apk-missing",
    }
    if code := post_build_errors.get(report.get("error")):
        summary["diagnostic_codes"].append(code)
    return summary


RUNTIME_PHASES = (
    "source",
    "device",
    "apks",
    "install-app",
    "install-test",
    "runner",
    "peer-build",
    "peer-ready",
    "peer-manifest",
    "instrumentation",
    "junit-validation",
    "receipts",
    "complete",
)


def runtime_summary(out: Path, report: dict) -> dict:
    """Export bounded diagnostic metadata without messages, stacks or paths."""
    scenario = report.get("scenario_id")
    methods = SCENARIOS.get(scenario, [])
    method = report.get("active_method")
    method = method if method in methods else None
    phase = report.get("phase")
    kind = report.get("error_kind")
    summary = {
        "schema_version": 1,
        "data_scope": report.get("data_scope")
        if report.get("data_scope") in ("local-usb-transport", "direct-lan", "emulator")
        else None,
        "unverified_boundaries": [
            "direct-device-lan-udp",
            "underlying-network-bypass",
            "wifi-cellular-handover",
        ]
        if report.get("data_scope") == "local-usb-transport"
        else [],
        "scenario_id": scenario if scenario in SCENARIOS else None,
        "status": report["status"]
        if report.get("status") in ("passed", "failed", "blocked")
        else "failed",
        "phase": phase if phase in RUNTIME_PHASES else "unknown",
        "method": method,
        "exception_kind": kind
        if kind
        in (
            "Blocked",
            "FileNotFoundError",
            "RuntimeError",
            "ValueError",
            "TimeoutExpired",
            "TimeoutError",
            "ConnectionError",
            "URLError",
            "HTTPError",
            "KeyboardInterrupt",
            "OSError",
            "KeyError",
        )
        else ("other" if kind else None),
        "completed_methods": [
            expected
            for expected in methods
            if any(
                check.get("id") == expected.split("#")[1]
                and check.get("passed") is True
                for check in report.get("checks", [])
            )
        ],
        "cleanup_passed": report.get("cleanup", {}).get("passed") is True,
        "diagnostic_codes": [],
        "instrumentation_status_codes": [],
        "instrumentation_final_codes": [],
        "test_frames": [],
    }
    signatures = {
        "disk-full": r"No space left on device",
        "dependency-resolution": r"Could not resolve |Could not download |failed to download|proxy.golang.org.*(?:timeout|refused)",
        "go-toolchain": r"toolchain.*(?:not available|verification failed)|requires go >=",
        "rust-compile": r"could not compile |error\[E\d+\]",
        "install-failed": r"INSTALL_FAILED_[A-Z_]+",
        "instrumentation-failed": r"INSTRUMENTATION_FAILED:|INSTRUMENTATION_ABORTED:|INSTRUMENTATION_RESULT: shortMsg=",
        "assertion-failed": r"(?:java.lang.AssertionError|junit.framework.AssertionFailedError)",
        "process-crash": r"FATAL EXCEPTION|Process crashed",
    }
    paths = [
        out / name
        for name in (
            "peer-build.log",
            "fixture-build.log",
            "peer.log",
            "fixture.log",
            "install-app.log",
            "install-test.log",
            "android-logcat.log",
        )
    ]
    if method:
        paths.append(out / f"test-{methods.index(method) + 1}" / "instrumentation.log")
    for path in paths:
        if not path.is_file():
            continue
        try:
            with path.open("rb") as stream:
                stream.seek(max(0, path.stat().st_size - 256 * 1024))
                output = stream.read().decode(errors="replace")
        except OSError:
            if "diagnostic-read-failed" not in summary["diagnostic_codes"]:
                summary["diagnostic_codes"].append("diagnostic-read-failed")
            continue
        for code, pattern in signatures.items():
            if code not in summary["diagnostic_codes"] and re.search(pattern, output):
                summary["diagnostic_codes"].append(code)
        if path.name != "instrumentation.log":
            continue
        for prefix, key in (
            ("INSTRUMENTATION_STATUS_CODE", "instrumentation_status_codes"),
            ("INSTRUMENTATION_CODE", "instrumentation_final_codes"),
        ):
            summary[key] = [
                int(code)
                for code in re.findall(
                    r"^" + prefix + r": (-?[0-9]{1,3})\s*$", output, re.M
                )[:16]
            ]
        class_name = method.split("#")[0]
        filename = class_name.rsplit(".", 1)[1] + ".kt"
        pattern = (
            r"^\s*at "
            + re.escape(class_name)
            + r"(?:\$[A-Za-z0-9_$]+)?\.[A-Za-z0-9_$<>]+\("
            + re.escape(filename)
            + r":([0-9]{1,6})\)\s*$"
        )
        lines = dict.fromkeys(int(line) for line in re.findall(pattern, output, re.M))
        summary["test_frames"] = [
            {"class": class_name, "line": line} for line in list(lines)[:16] if line > 0
        ]
    error_codes = {
        "owned fixture exited before readiness": "fixture-exited",
        "owned fixture readiness timed out": "fixture-readiness-timeout",
        "expected exactly one installed RIPDPI test runner": "runner-mismatch",
        "missing, duplicate, unexpected or incomplete instrumentation evidence": "instrumentation-incomplete",
    }
    if code := error_codes.get(report.get("error")):
        summary["diagnostic_codes"].append(code)
    return summary


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=SCENARIOS)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--out-dir", required=True, type=Path)
    parser.add_argument(
        "--serial", required=True, help="explicit leased Android test emulator serial"
    )
    parser.add_argument(
        "--physical-device",
        action="store_true",
        help="authorize a fresh owned acceptance installation on the selected physical serial",
    )
    parser.add_argument(
        "--physical-adb-bridge",
        action="store_true",
        help="run the named local USB transport scenarios without a direct LAN claim",
    )
    parser.add_argument(
        "--physical-host",
        type=local_host,
        help="exact private LAN address of this fixture host",
    )
    parser.add_argument(
        "--physical-peer",
        type=local_host,
        help="exact private LAN address of the selected device",
    )
    parser.add_argument(
        "--owned-installation",
        type=Path,
        help="private ownership receipt from an earlier fresh acceptance install",
    )
    parser.add_argument(
        "--hysteria-binary",
        type=Path,
        help="verified pinned Hysteria2 v2.13.0 peer binary",
    )
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--prepared-dir", type=Path)
    parser.add_argument(
        "--xray-artifact-dir",
        type=Path,
        help="verified real libXray AAR producer directory",
    )
    parser.add_argument(
        "--xray-debug",
        action="store_true",
        help="opt-in local real Xray client and owned peer diagnostic logs",
    )
    parser.add_argument("--xray-host", type=local_host, default="10.0.2.2")
    parser.add_argument("--xray-control-host", type=local_host)
    parser.add_argument("--xray-control-port", type=int)
    parser.add_argument(
        "--host-control-host",
        type=local_host,
        default="127.0.0.1",
        help="host-side address for VM receipts",
    )
    parser.add_argument("--fixture-host", type=local_host, default="10.0.2.2")
    parser.add_argument("--fixture-control-port", type=int)
    args = parser.parse_args()
    if (
        not TOKEN.fullmatch(args.run_id)
        or not TOKEN.fullmatch(args.serial)
        or (
            not args.physical_device
            and not re.fullmatch(r"emulator-[0-9]+", args.serial)
        )
    ):
        parser.error("run ID must be a safe token and serial must identify an emulator")
    if not args.prepare and not args.scenario:
        parser.error("--scenario is required unless --prepare is used")
    for port in (args.xray_control_port, args.fixture_control_port):
        if port is not None and not 1 <= port <= 65535:
            parser.error("control ports must be in 1..65535")
    usb_scenario = bool(args.scenario and args.scenario.startswith("android-usb-"))
    if args.physical_adb_bridge:
        if not args.physical_device or args.physical_host or args.physical_peer:
            parser.error(
                "USB transport requires --physical-device and no LAN addresses"
            )
        if not args.prepare and not usb_scenario:
            parser.error("USB transport requires a named android-usb scenario")
    elif usb_scenario:
        parser.error("android-usb scenarios require --physical-adb-bridge")
    if args.physical_device and not args.prepare:
        if not args.physical_adb_bridge:
            if not args.physical_host or not args.physical_peer:
                parser.error(
                    "physical acceptance requires --physical-host and --physical-peer private LAN addresses"
                )
            if args.physical_host == args.physical_peer or any(
                ipaddress.ip_address(host).is_loopback
                for host in (args.physical_host, args.physical_peer)
            ):
                parser.error(
                    "physical data endpoints require distinct non-loopback private LAN addresses"
                )
        if args.scenario == "android-network":
            parser.error(
                "android-network uses the emulator fixture; select an owned physical transport scenario"
            )
    if (
        args.scenario in ("android-hysteria", "android-usb-hysteria")
        and not args.hysteria_binary
    ):
        parser.error("Hysteria acceptance requires --hysteria-binary")
    return args


def main() -> int:
    args = arguments()
    protocol_scenario = (
        args.scenario.replace("android-usb-", "android-") if args.scenario else None
    )
    if args.physical_device and not args.prepare:
        lock_root = Path(
            os.environ.get(
                "RIPDPI_ANDROID_DEVICE_LOCK_ROOT", os.environ.get("TMPDIR", "/tmp")
            )
        ).resolve(strict=True)
        lock_name = f"ripdpi-android-device-{args.serial}.lock"
        guard = str(ROOT / "test-lab/scripts/run-with-android-device-lock.py")
        nonce = os.environ.get("RIPDPI_ANDROID_DEVICE_LOCK_AUTH")
        if nonce is None:
            os.execv(
                sys.executable,
                [
                    sys.executable,
                    guard,
                    "run",
                    "--lock-root",
                    str(lock_root),
                    "--lock-name",
                    lock_name,
                    sys.executable,
                    str(Path(__file__).resolve()),
                    *sys.argv[1:],
                ],
            )
        subprocess.run(
            [
                sys.executable,
                guard,
                "verify",
                "--lock-root",
                str(lock_root),
                "--lock-name",
                lock_name,
                "--supervisor-pid",
                os.environ.get("RIPDPI_ANDROID_DEVICE_LOCK_SUPERVISOR_PID", "0"),
                "--nonce",
                nonce,
            ],
            check=True,
        )
    args.out_dir.mkdir(parents=True, exist_ok=False)
    out = args.out_dir.resolve()
    os.chmod(out, 0o700)
    report = {
        "schema_version": 1,
        "run_id": args.run_id,
        "scenario_id": args.scenario or "android-prepare",
        "source_sha": "",
        "tier": "android-tun",
        "status": "failed",
        "phase": "source",
        "checks": [],
        "artifacts": [],
        "peer_identity": {},
        "cleanup": {"passed": False},
    }
    owned: list[OwnedProcess] = []
    installed = False
    nginx = None
    hysteria = None
    udp_bridge = None
    migration_origin = None
    migration_identity = {}
    reserve_control_port = 0
    awg_peer = None
    installation = None
    reverse_ports = []
    test_package = PACKAGE + ".test"
    adb = [os.environ.get("ADB", "adb"), "-s", args.serial]

    def command(command_args: list[str], filename: str, **kwargs) -> str:
        return run(command_args, out / filename, **kwargs)

    def stop_signal(_signum, _frame):
        raise RuntimeError("acceptance interrupted")

    signal.signal(signal.SIGTERM, stop_signal)
    try:
        source = command(["git", "rev-parse", "HEAD"], "source.log").strip()
        report["source_sha"] = source
        report["source_tree_sha256"] = source_fingerprint()
        report["phase"] = "device"
        devices = command([adb[0], "devices"], "devices.log")
        if not re.search(r"^" + re.escape(args.serial) + r"\s+device$", devices, re.M):
            raise Blocked("selected emulator is absent, offline, or not authorized")
        device_type = command(
            adb + ["shell", "getprop", "ro.kernel.qemu"], "device-kind.log"
        ).strip()
        if device_type != "1" and not args.physical_device:
            raise Blocked("the selected serial is not a ready Android Emulator")
        abi = command(
            adb + ["shell", "getprop", "ro.product.cpu.abi"], "device-abi.log"
        ).strip()
        if abi not in ("arm64-v8a", "x86_64"):
            raise Blocked("the emulator ABI is not supported by this acceptance lane")
        report["device"] = {
            "serial": args.serial,
            "abi": abi,
            "physical": device_type != "1",
        }
        if args.physical_device:
            args.xray_host = args.physical_host or "127.0.0.1"
        report["phase"] = "apks"
        metadata = (
            prepared(args.prepared_dir, abi, source)
            if args.prepared_dir
            else prepare(out, abi, source, args.xray_artifact_dir)
        )
        report["apk_sha256"] = {
            key: item["sha256"] for key, item in metadata["apks"].items()
        }
        if args.prepare:
            report["status"] = "passed"
            report["checks"].append({"id": "build", "passed": True})
            return 0
        if args.physical_device:
            sdk = Path(
                os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))
            )
            signers = sorted(sdk.glob("build-tools/*/apksigner"))
            if not signers:
                raise Blocked(
                    "Android SDK apksigner is required for physical installation ownership"
                )
            installation = OwnedInstallation(
                adb,
                args.owned_installation or out / "installation-owner.json",
                metadata,
                signers[-1],
            )
            installation.prepare()
            report["installation_scope"] = "owned-fresh-installation"
        for kind in ("app", "test"):
            report["phase"] = "install-" + kind
            command(
                adb + ["install", "-r", "-t", metadata["apks"][kind]["path"]],
                f"install-{kind}.log",
            )
            installed = True
            if installation is not None:
                installation.confirm(kind)
        report["phase"] = "runner"
        components = command(
            adb + ["shell", "pm", "list", "instrumentation"],
            "instrumentation-components.log",
        )
        component = owned_instrumentation_component(components)
        test_package = component.split("/", 1)[0]
        instrumentation = {
            "ripdpi.runNetworkTests": "true",
            "ripdpi.acceptanceRunId": args.run_id,
        }
        if args.physical_device:
            instrumentation["ripdpi.authorizedPhysicalDevice"] = "true"
        if args.physical_adb_bridge:
            instrumentation["ripdpi.usbDataScope"] = "true"
            report["data_scope"] = "local-usb-transport"
            report["unverified_boundaries"] = [
                "direct-device-lan-udp",
                "underlying-network-bypass",
                "wifi-cellular-handover",
            ]
        else:
            report["data_scope"] = "direct-lan" if args.physical_device else "emulator"

        receipt_port = 0
        receipt_host = args.host_control_host
        if protocol_scenario in (
            "android-xray",
            "android-transactions",
            "android-hysteria",
            "android-migration",
        ):
            receipt_port = args.xray_control_port or 0
            if not receipt_port:
                if (
                    args.xray_host != "10.0.2.2" and not args.physical_device
                ) or args.xray_control_host:
                    raise Blocked("external Xray endpoints require --xray-control-port")
                report["phase"] = "peer-build"
                peer_binary = out / "xray-peer"
                command(
                    heavy_command(
                        [
                            "env",
                            "GOMAXPROCS=2",
                            "go",
                            "build",
                            "-p",
                            "2",
                            "-mod=readonly",
                            "-o",
                            str(peer_binary),
                            ".",
                        ]
                    ),
                    "peer-build.log",
                    cwd=ROOT / "scripts/fixtures/xray-provider-peer",
                    timeout=1200,
                )
                report["phase"] = "peer-ready"
                ready = out / "peer-ready.json"
                child = OwnedProcess(
                    [
                        str(peer_binary),
                        "--ready-file",
                        str(ready),
                        "--run-id",
                        args.run_id,
                    ]
                    + (
                        ["--tls-directory", str(out / "peer-tls")]
                        if protocol_scenario
                        in (
                            "android-transactions",
                            "android-hysteria",
                            "android-migration",
                        )
                        else []
                    )
                    + (
                        [
                            "--bind-host",
                            args.physical_host,
                            "--advertise-host",
                            args.physical_host,
                        ]
                        if args.physical_host
                        else []
                    )
                    + (["--debug"] if args.xray_debug else []),
                    out / "peer.log",
                )
                owned.append(child)
                peer_ready = wait_json(ready, child)
                receipt_port = peer_ready["controlPort"]
                report["peer_identity"] = {
                    "implementation": "xray-core",
                    "version": peer_ready["version"],
                    "binary_sha256": digest(peer_binary),
                }
            else:
                report["peer_identity"] = {
                    "implementation": "xray-core",
                    "owner": "vm-controller",
                }
            instrumentation.update(
                {
                    "ripdpi.xrayFixtureHost": args.xray_host,
                    "ripdpi.xrayControlHost": args.xray_control_host
                    or ("127.0.0.1" if args.physical_device else args.xray_host),
                    "ripdpi.xrayFixturePort": str(receipt_port),
                }
            )
            if args.xray_debug:
                instrumentation["ripdpi.xrayDebug"] = "true"
            report["phase"] = "peer-manifest"
            peer_manifest = fetch(receipt_host, receipt_port, "manifest")
            if peer_manifest.get("runId") != args.run_id:
                raise Blocked(
                    "Xray peer belongs to a different run; launch peer with --run-id"
                )
            report["peer_identity"].update(
                version=peer_manifest["version"], run_id=args.run_id
            )
            (out / "peer-manifest.json").write_text(json.dumps(peer_manifest, indent=2))
            if protocol_scenario == "android-transactions":
                if not owned:
                    raise Blocked("TLS acceptance requires an owned local peer")
                nginx = NginxFixture(
                    out / "nginx",
                    out / "peer-tls",
                    peer_manifest["tlsBackendPort"],
                    bind_host=args.physical_host or "127.0.0.1",
                )
                nginx.start()
                report["peer_identity"]["tls_frontend"] = nginx.identity
                instrumentation["ripdpi.nginxTlsPort"] = str(nginx.port)
                instrumentation["ripdpi.nginxCertificate"] = peer_manifest[
                    "qualityCertificate"
                ]
            if protocol_scenario == "android-hysteria":
                verify_binary(args.hysteria_binary)
                hysteria = HysteriaFixture(
                    out / "hysteria",
                    out / "peer-tls",
                    args.hysteria_binary,
                    args.physical_host or "127.0.0.1",
                    args.physical_peer or "127.0.0.1",
                    peer_manifest,
                    receipt_port,
                    advertise_host=args.xray_host,
                )
                hysteria.start()
                if args.physical_adb_bridge:
                    from android_udp_bridge import UdpBridge

                    udp_bridge = UdpBridge(
                        host="127.0.0.1", udp_port=hysteria.gate.port
                    )
                    udp_bridge.start()
                    instrumentation.update(
                        {
                            "ripdpi.usbUdpBridgePort": str(udp_bridge.port),
                            "ripdpi.usbUdpBridgeToken": udp_bridge.token,
                        }
                    )
                receipt_port = hysteria.port
                instrumentation["ripdpi.xrayFixturePort"] = str(receipt_port)
                report["peer_identity"]["udp_transport"] = {
                    "implementation": "hysteria",
                    "version": "2.13.0",
                    "binary_sha256": digest(args.hysteria_binary),
                }
            if protocol_scenario == "android-migration":
                from android_migration_fixture import MigrationFixture

                if not owned:
                    raise Blocked("migration acceptance requires an owned local peer")
                migration_peers = {
                    name: start_migration_peer(
                        peer_binary,
                        out,
                        args.run_id,
                        name,
                        owned,
                        args.physical_host if args.physical_device else None,
                    )
                    for name in ("reserve", "replacement")
                }
                if (
                    len(
                        {
                            peer_manifest["uuid"],
                            *(peer["uuid"] for peer in migration_peers.values()),
                        }
                    )
                    != 3
                ):
                    raise RuntimeError(
                        "migration peers must use different client UUIDs"
                    )
                reserve_manifest = migration_peers["reserve"]
                replacement_manifest = migration_peers["replacement"]
                reserve_control_port = reserve_manifest["controlPort"]
                replacement_control_port = replacement_manifest["controlPort"]
                migration_identity = {
                    "migration_id": str(uuid.uuid4()),
                    "delivery_id": str(uuid.uuid4()),
                    "receipt_key": secrets.token_hex(32),
                }
                migration_origin = MigrationFixture(
                    out / "migration",
                    out / "peer-tls",
                    lambda tls_port, token: migration_payloads(
                        peer_manifest,
                        reserve_manifest,
                        replacement_manifest,
                        args.xray_host,
                        tls_port,
                        token,
                        migration_identity,
                    ),
                )
                migration_origin.start()
                instrumentation.update(
                    {
                        "ripdpi.migrationControlHost": "127.0.0.1"
                        if args.physical_device
                        else "10.0.2.2",
                        "ripdpi.migrationControlPort": str(
                            migration_origin.control_port
                        ),
                        "ripdpi.migrationHttpsPort": str(migration_origin.port),
                        "ripdpi.migrationCertificate": peer_manifest[
                            "qualityCertificate"
                        ],
                        "ripdpi.oldControlPort": str(receipt_port),
                        "ripdpi.reserveControlPort": str(reserve_control_port),
                        "ripdpi.replacementControlPort": str(replacement_control_port),
                    }
                )
                report["peer_identity"]["replacement"] = {
                    "implementation": "xray-core",
                    "version": replacement_manifest["version"],
                }
            if args.physical_device:
                ports = [receipt_port]
                if migration_origin is not None:
                    ports.extend(
                        [
                            reserve_control_port,
                            replacement_control_port,
                            migration_origin.port,
                            migration_origin.control_port,
                        ]
                    )
                if args.physical_adb_bridge:
                    ports.extend(
                        [
                            peer_manifest["tcpPort"],
                            peer_manifest["xhttpPort"],
                            peer_manifest["dnsHttpPort"],
                        ]
                    )
                    if nginx is not None:
                        ports.append(nginx.port)
                    if udp_bridge is not None:
                        ports.append(udp_bridge.port)
                    if migration_origin is not None:
                        ports.extend(
                            [
                                reserve_manifest["tcpPort"],
                                replacement_manifest["tcpPort"],
                            ]
                        )
                for port in sorted(set(ports)):
                    command(
                        adb + ["reverse", "--no-rebind", f"tcp:{port}", f"tcp:{port}"],
                        f"reverse-{port}.log",
                    )
                    reverse_ports.append(port)

        elif protocol_scenario == "android-awg":
            report["phase"] = "peer-build"
            peer_binary = out / "awg-peer"
            command(
                heavy_command(
                    [
                        "env",
                        "GOMAXPROCS=2",
                        "go",
                        "build",
                        "-p",
                        "2",
                        "-mod=readonly",
                        "-o",
                        str(peer_binary),
                        ".",
                    ]
                ),
                "peer-build.log",
                cwd=ROOT / "scripts/fixtures/amneziawg-peer",
                timeout=1800,
            )
            env = awg_peer_environment(args.physical_host, args.physical_peer)
            awg_peer = OwnedProcess([str(peer_binary)], out / "peer.log", env=env)
            owned.append(awg_peer)
            peer_manifest = wait_json(out / "peer.log", awg_peer)
            if peer_manifest.get("mode") != "http-tun":
                raise RuntimeError(
                    "AWG peer did not start full TUN HTTP acceptance mode"
                )
            receipt_port = peer_manifest["controlPort"]
            if args.physical_adb_bridge:
                from android_udp_bridge import UdpBridge

                udp_bridge = UdpBridge(
                    host="127.0.0.1",
                    udp_port=int(peer_manifest["endpoint"].rsplit(":", 1)[1]),
                )
                udp_bridge.start()
                instrumentation.update(
                    {
                        "ripdpi.usbUdpBridgePort": str(udp_bridge.port),
                        "ripdpi.usbUdpBridgeToken": udp_bridge.token,
                    }
                )
            instrumentation.update(
                {
                    "ripdpi.awgControlHost": "127.0.0.1"
                    if args.physical_device
                    else "10.0.2.2",
                    "ripdpi.awgControlPort": str(receipt_port),
                    "ripdpi.awgEndpointHost": "127.0.0.1"
                    if args.physical_adb_bridge
                    else (args.physical_host or "10.0.2.2"),
                }
            )
            report["peer_identity"] = {
                "implementation": "amneziawg-go",
                "wire_profile": "awg-3.1-ranges-v1",
                "binary_sha256": digest(peer_binary),
            }
            if args.physical_device:
                command(
                    adb
                    + [
                        "reverse",
                        "--no-rebind",
                        f"tcp:{receipt_port}",
                        f"tcp:{receipt_port}",
                    ],
                    "reverse-awg-control.log",
                )
                reverse_ports.append(receipt_port)
                if udp_bridge is not None:
                    command(
                        adb
                        + [
                            "reverse",
                            "--no-rebind",
                            f"tcp:{udp_bridge.port}",
                            f"tcp:{udp_bridge.port}",
                        ],
                        "reverse-udp-bridge.log",
                    )
                    reverse_ports.append(udp_bridge.port)
        else:
            receipt_port = args.fixture_control_port or 0
            if not receipt_port:
                if args.fixture_host != "10.0.2.2":
                    raise Blocked(
                        "external network fixture requires --fixture-control-port"
                    )
                report["phase"] = "peer-build"
                command(
                    [
                        "cargo",
                        "build",
                        "--locked",
                        "--manifest-path",
                        str(ROOT / "native/rust/Cargo.toml"),
                        "-p",
                        "local-network-fixture",
                        "--target-dir",
                        str(out / "fixture-target"),
                    ],
                    "fixture-build.log",
                    timeout=1200,
                )
                env = os.environ.copy()
                # Each listener reserves its own random port. No shared PID file or fixed port is touched.
                for name in (
                    "TCP_ECHO",
                    "UDP_ECHO",
                    "TLS_ECHO",
                    "DNS_UDP",
                    "DNS_HTTP",
                    "DNS_DOT",
                    "DNS_DNSCRYPT",
                    "DNS_DOQ",
                    "DNS_ODOH_PROXY",
                    "DNS_ODOH_TARGET",
                    "SOCKS5",
                    "CONTROL",
                ):
                    env[f"RIPDPI_FIXTURE_{name}_PORT"] = "0"
                env.update(
                    RIPDPI_FIXTURE_BIND_HOST="127.0.0.1",
                    RIPDPI_FIXTURE_ANDROID_HOST="10.0.2.2",
                    RIPDPI_FIXTURE_DNS_ANSWER_IPV4="10.0.2.2",
                )
                report["phase"] = "peer-ready"
                child = OwnedProcess(
                    [str(out / "fixture-target/debug/local-network-fixture")],
                    out / "fixture.log",
                    env=env,
                )
                owned.append(child)
                manifest = wait_json(out / "fixture.log", child)
                receipt_port = manifest["controlPort"]
            report["phase"] = "peer-manifest"
            manifest = fetch(receipt_host, receipt_port, "manifest")
            (out / "fixture-manifest.json").write_text(json.dumps(manifest, indent=2))
            report["peer_identity"] = {
                "implementation": "repository-local-network-fixture",
                "source_sha": source,
            }
            instrumentation.update(
                {
                    "ripdpi.fixtureControlHost": args.fixture_host,
                    "ripdpi.fixtureControlPort": str(receipt_port),
                }
            )
        for index, method in enumerate(SCENARIOS[args.scenario]):
            report["phase"] = "instrumentation"
            report["active_method"] = method
            test_dir = out / f"test-{index + 1}"
            test_dir.mkdir()
            launch = adb + [
                "shell",
                "am",
                "instrument",
                "-w",
                "-r",
                "-e",
                "class",
                method,
            ]
            for key, value in instrumentation.items():
                launch.extend(["-e", key, value])
            launch.append(component)
            output = run(launch, test_dir / "instrumentation.log", timeout=600)
            parse_instrumentation(output, method)
            if protocol_scenario in (
                "android-xray",
                "android-transactions",
                "android-hysteria",
                "android-migration",
            ):
                for endpoint in (
                    "receipts",
                    "direct-receipts",
                    "dns-receipts",
                    "dns-http-receipts",
                    "request-receipts",
                    "transport-receipts",
                ):
                    (test_dir / f"{endpoint}.json").write_text(
                        json.dumps(
                            fetch(receipt_host, receipt_port, endpoint), indent=2
                        )
                    )
            elif protocol_scenario == "android-awg":
                for endpoint in ("transport-receipts", "dns-receipts"):
                    (test_dir / f"{endpoint}.json").write_text(
                        json.dumps(
                            fetch(receipt_host, receipt_port, endpoint), indent=2
                        )
                    )
            elif "NetworkPathE2ETest" in method:
                run(
                    adb
                    + [
                        "exec-out",
                        "run-as",
                        PACKAGE,
                        "cat",
                        f"files/local-acceptance-{args.run_id}.json",
                    ],
                    test_dir / "fixture-receipts.json",
                )
            if protocol_scenario == "android-migration":
                for endpoint in ("transport-receipts", "dns-receipts"):
                    (test_dir / f"replacement-{endpoint}.json").write_text(
                        json.dumps(
                            fetch(receipt_host, replacement_control_port, endpoint),
                            indent=2,
                        )
                    )
                (test_dir / "update-receipts.json").write_text(
                    json.dumps(
                        fetch(receipt_host, migration_origin.control_port, "receipts"),
                        indent=2,
                    )
                )
                raw = run(
                    adb
                    + [
                        "exec-out",
                        "run-as",
                        PACKAGE,
                        "cat",
                        f"files/migration-acceptance-{args.run_id}.json",
                    ],
                    test_dir / "device-migration-receipt.json",
                )
                validate_migration_receipt(json.loads(raw), migration_identity)
            if protocol_scenario == "android-awg":
                finished = fetch(receipt_host, receipt_port, "finish", "POST")
                if (
                    finished != {"wireAccepted": True}
                    or awg_peer.process.wait(timeout=10) != 0
                ):
                    raise RuntimeError("independent AWG wire acceptance failed")
                observations = [
                    json.loads(line)["wire_observation"]
                    for line in (out / "peer.log").read_text().splitlines()
                    if line.startswith('{"wire_observation":')
                ]
                if (
                    len(observations) != 1
                    or observations[0].get("wire_profile") != "awg-3.1-ranges-v1"
                ):
                    raise RuntimeError("missing AWG wire observation")
                (test_dir / "wire-observation.json").write_text(
                    json.dumps(observations[0], indent=2)
                )
            report["phase"] = "junit-validation"
            write_junit(test_dir / "results.xml", method)
            run(
                [
                    sys.executable,
                    str(ROOT / "scripts/ci/validate_android_junit_results.py"),
                    str(test_dir),
                    "--require-test",
                    method,
                    "--expected-count",
                    "1",
                    "--expected-total-count",
                    "1",
                    "--forbid-skips",
                ],
                test_dir / "validation.log",
            )
            report["checks"].append({"id": method.split("#")[1], "passed": True})
        if udp_bridge is not None:
            evidence = udp_bridge.receipts()
            if (
                evidence["connections"] != 1
                or evidence["unauthorized"] != 0
                or min(evidence["datagramsToPeer"], evidence["datagramsToDevice"]) < 1
            ):
                raise RuntimeError(
                    "USB datagram path has no complete owned duplex evidence"
                )
        report["phase"] = "complete"
        report["status"] = "passed"
    except (Blocked, FileNotFoundError) as error:
        report["status"] = "blocked"
        report["error"] = str(error)
        report["error_kind"] = type(error).__name__
    except (Exception, KeyboardInterrupt) as error:
        report["error"] = str(error)
        report["error_kind"] = type(error).__name__
    finally:
        cleanup_errors = []
        if installed:
            try:
                command(
                    adb
                    + [
                        "logcat",
                        "-d",
                        "-s",
                        "ripdpi-native:V",
                        "ripdpi-tunnel-native:V",
                        "AndroidRuntime:E",
                        "DnsFailover:V",
                        "XrayPeerRecovery:I",
                        "XrayBuilderInputs:I",
                        "VpnDnsRecovery:I",
                        "NetworkHandover:I",
                        "XrayNativeTrace:I",
                        "XrayProtect:V",
                        "GoLog:V",
                        "*:S",
                    ],
                    "android-logcat.log",
                    timeout=15,
                )
            except Exception:
                pass  # Diagnostic collection does not change the test verdict.
            for package in (test_package, PACKAGE):
                try:
                    command(
                        adb + ["shell", "am", "force-stop", package],
                        f"cleanup-{package}.log",
                        timeout=15,
                    )
                    stopped = subprocess.run(
                        adb + ["shell", "pidof", package],
                        capture_output=True,
                        text=True,
                        timeout=10,
                    )
                    if stopped.returncode != 1 or stopped.stdout.strip():
                        raise RuntimeError(
                            f"package still running after cleanup: {package}"
                        )
                except Exception as error:
                    cleanup_errors.append(str(error))
            try:
                services = command(
                    adb + ["shell", "dumpsys", "activity", "services", PACKAGE],
                    "cleanup-services.log",
                    timeout=15,
                )
                if "ServiceRecord{" in services:
                    raise RuntimeError("RIPDPI service record remains after cleanup")
            except Exception as error:
                cleanup_errors.append(str(error))
        for port in reverse_ports:
            try:
                command(
                    adb + ["reverse", "--remove", f"tcp:{port}"],
                    f"reverse-cleanup-{port}.log",
                )
            except Exception as error:
                cleanup_errors.append(str(error))
        if udp_bridge is not None:
            try:
                (out / "usb-datagram-receipts.json").write_text(
                    json.dumps(udp_bridge.receipts(), indent=2)
                )
            except Exception as error:
                cleanup_errors.append(str(error))
            try:
                udp_bridge.close()
            except Exception as error:
                cleanup_errors.append(str(error))
        if migration_origin is not None:
            try:
                migration_origin.close()
            except Exception as error:
                cleanup_errors.append(str(error))
        if hysteria is not None:
            try:
                hysteria.close()
            except Exception as error:
                cleanup_errors.append(str(error))
        if nginx is not None:
            try:
                nginx.close()
            except Exception as error:
                cleanup_errors.append(str(error))
        for process in reversed(owned):
            try:
                process.close()
            except Exception as error:
                cleanup_errors.append(str(error))
        report["cleanup"] = {"passed": not cleanup_errors, "errors": cleanup_errors}
        if cleanup_errors:
            report["status"] = "failed"
        # Binaries, build trees and fixture credential material are private, not report attachments.
        report["artifacts"] = sorted(
            str(path.relative_to(out))
            for path in out.rglob("*")
            if path.is_file()
            and "fixture-target" not in path.parts
            and path.suffix in (".log", ".xml", ".json")
            and path.name not in ("result.json", "installation-owner.json")
        )
        (out / "result.json").write_text(json.dumps(report, indent=2) + "\n")
        if args.prepare:
            summary = preparation_summary(out, report)
            (out / "preparation-summary.json").write_text(
                json.dumps(summary, indent=2) + "\n"
            )
            print(json.dumps(summary))
        else:
            summary = runtime_summary(out, report)
            try:
                (out / "runtime-summary.json").write_text(
                    json.dumps(summary, indent=2) + "\n"
                )
            except OSError:
                summary["diagnostic_codes"].append("summary-write-failed")
            print(json.dumps(summary))
    return (
        0
        if report["status"] == "passed"
        else (2 if report["status"] == "blocked" else 1)
    )


if __name__ == "__main__":
    raise SystemExit(main())
