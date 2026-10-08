#!/usr/bin/env python3
"""Run exact Android TUN acceptance methods against owned local peers.

The caller must select a disposable emulator with --serial. No existing AVD is
reset or removed. Remote fixtures belong to the VM controller and are not stopped
by this adapter. Host fixtures are child processes with fresh output directories.
"""

from __future__ import annotations

import argparse
import hashlib
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

ROOT = Path(__file__).resolve().parents[2]
PREFIX = "com.poyka.ripdpi.e2e."
SCENARIOS = {
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
PACKAGE = "com.poyka.ripdpi"
TOKEN = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}\Z")


class Blocked(RuntimeError):
    """A required runtime prerequisite is absent."""


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


def fetch(host: str, port: int, endpoint: str) -> object:
    # Never use ambient HTTP proxies for local fixture control.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(f"http://{host}:{port}/{endpoint}", timeout=5) as response:
        return json.load(response)


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
        [
            str(ROOT / "gradlew"),
            ":app:assembleGithubFullDebug",
            ":app:assembleGithubFullDebugAndroidTest",
            f"-Pripdpi.nativeAbisOverride={abi}",
        ]
        + (
            [f"-Pripdpi.prebuiltXrayAarDir={xray_artifact_dir.resolve()}"]
            if xray_artifact_dir
            else []
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


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=SCENARIOS)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--out-dir", required=True, type=Path)
    parser.add_argument(
        "--serial", required=True, help="explicit disposable Android emulator serial"
    )
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--prepared-dir", type=Path)
    parser.add_argument(
        "--xray-artifact-dir",
        type=Path,
        help="verified real libXray AAR producer directory",
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
    if not TOKEN.fullmatch(args.run_id) or not re.fullmatch(
        r"emulator-[0-9]+", args.serial
    ):
        parser.error("run ID must be a safe token and serial must identify an emulator")
    if not args.prepare and not args.scenario:
        parser.error("--scenario is required unless --prepare is used")
    for port in (args.xray_control_port, args.fixture_control_port):
        if port is not None and not 1 <= port <= 65535:
            parser.error("control ports must be in 1..65535")
    return args


def main() -> int:
    args = arguments()
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
        "checks": [],
        "artifacts": [],
        "peer_identity": {},
        "cleanup": {"passed": False},
    }
    owned: list[OwnedProcess] = []
    installed = False
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
        devices = command([adb[0], "devices"], "devices.log")
        if not re.search(r"^" + re.escape(args.serial) + r"\s+device$", devices, re.M):
            raise Blocked("selected emulator is absent, offline, or not authorized")
        device_type = command(
            adb + ["shell", "getprop", "ro.kernel.qemu"], "device-kind.log"
        ).strip()
        if device_type != "1":
            raise Blocked("the selected serial is not a ready Android Emulator")
        abi = command(
            adb + ["shell", "getprop", "ro.product.cpu.abi"], "device-abi.log"
        ).strip()
        if abi not in ("arm64-v8a", "x86_64"):
            raise Blocked("the emulator ABI is not supported by this acceptance lane")
        report["device"] = {"serial": args.serial, "abi": abi}
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
        for kind in ("app", "test"):
            command(
                adb + ["install", "-r", "-t", metadata["apks"][kind]["path"]],
                f"install-{kind}.log",
            )
        installed = True
        components = command(
            adb + ["shell", "pm", "list", "instrumentation"],
            "instrumentation-components.log",
        )
        matches = re.findall(
            r"^instrumentation:([A-Za-z0-9_.]+/[A-Za-z0-9_.]+) \(target="
            + re.escape(PACKAGE)
            + r"\)\s*$",
            components,
            re.M,
        )
        if len(matches) != 1:
            raise Blocked("expected exactly one installed RIPDPI test runner")
        component = matches[0]
        test_package = component.split("/", 1)[0]
        instrumentation = {
            "ripdpi.runNetworkTests": "true",
            "ripdpi.acceptanceRunId": args.run_id,
        }
        receipt_port = 0
        receipt_host = args.host_control_host
        if args.scenario == "android-xray":
            receipt_port = args.xray_control_port or 0
            if not receipt_port:
                if args.xray_host != "10.0.2.2" or args.xray_control_host:
                    raise Blocked("external Xray endpoints require --xray-control-port")
                peer_binary = out / "xray-peer"
                command(
                    ["go", "build", "-mod=readonly", "-o", str(peer_binary), "."],
                    "peer-build.log",
                    cwd=ROOT / "scripts/fixtures/xray-provider-peer",
                    timeout=1200,
                )
                ready = out / "peer-ready.json"
                child = OwnedProcess(
                    [
                        str(peer_binary),
                        "--ready-file",
                        str(ready),
                        "--run-id",
                        args.run_id,
                    ],
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
                    "ripdpi.xrayControlHost": args.xray_control_host or args.xray_host,
                    "ripdpi.xrayFixturePort": str(receipt_port),
                }
            )
            peer_manifest = fetch(receipt_host, receipt_port, "manifest")
            if peer_manifest.get("runId") != args.run_id:
                raise Blocked(
                    "Xray peer belongs to a different run; launch peer with --run-id"
                )
            report["peer_identity"].update(
                version=peer_manifest["version"], run_id=args.run_id
            )
            (out / "peer-manifest.json").write_text(json.dumps(peer_manifest, indent=2))
        else:
            receipt_port = args.fixture_control_port or 0
            if not receipt_port:
                if args.fixture_host != "10.0.2.2":
                    raise Blocked(
                        "external network fixture requires --fixture-control-port"
                    )
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
                child = OwnedProcess(
                    [str(out / "fixture-target/debug/local-network-fixture")],
                    out / "fixture.log",
                    env=env,
                )
                owned.append(child)
                manifest = wait_json(out / "fixture.log", child)
                receipt_port = manifest["controlPort"]
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
            if args.scenario == "android-xray":
                for endpoint in (
                    "receipts",
                    "direct-receipts",
                    "dns-receipts",
                    "request-receipts",
                ):
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
        report["status"] = "passed"
    except (Blocked, FileNotFoundError) as error:
        report["status"] = "blocked"
        report["error"] = str(error)
    except (Exception, KeyboardInterrupt) as error:
        report["error"] = str(error)
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
            and path.name != "result.json"
        )
        (out / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    return (
        0
        if report["status"] == "passed"
        else (2 if report["status"] == "blocked" else 1)
    )


if __name__ == "__main__":
    raise SystemExit(main())
