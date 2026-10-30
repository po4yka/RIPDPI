#!/usr/bin/env python3
"""Run real AWG JNI interop on a leased arm64 emulator. Invoke through build-gate."""

import argparse
import base64
from contextlib import contextmanager
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import subprocess
import sys
import tarfile
import time

ROOT = Path(__file__).resolve().parents[2]
RUST = ROOT / "native/rust"
PEER_COMMIT = "b5928efb6ca19f0153958460c3d141f04abc5c2e"
METHOD = "com.poyka.ripdpi.testing.awginterop.AwgNativeInteropTest#nativeCoreExchangesDualStackPayloads"
FEATURE_METHOD = "com.poyka.ripdpi.testing.awginterop.AwgNativeInteropTest#nativeCoreExercisesFeatureScenario"
NEGATIVE_METHOD = "com.poyka.ripdpi.testing.awginterop.AwgNativeInteropTest#nativeCoreRefusesMismatchedHeaderProtection"
PACKAGE = "com.poyka.ripdpi.testing.awginterop.test"
RUNNER = f"{PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
# Per profile: TCP bytes per family and session, lockstep UDP datagrams per family and session.
PROFILES = {"awg-3.1-ranges-v1": (4096, 0), "amneziavpn-5x": (4 << 20, 512)}
PROFILES.update({name: (4096, 0) for name in ("v3-cpa", "v3-trailers", "v3-cpa-trailers", "v3-aligned", "v3-fixed-headers")})
# Public fixture keys: the peer uses 0x03 * 32; the negative control sends 0x04 * 32.
HEADER_KEY = base64.b64encode(bytes([3]) * 32).decode()
WRONG_HEADER_KEY = base64.b64encode(bytes([4]) * 32).decode()
GO_VERSION = "go1.27.3"


def checked(command, *, cwd=ROOT, env=None, timeout=900):
    return subprocess.run(command, cwd=cwd, env=env, check=True, timeout=timeout, text=True, capture_output=True).stdout.strip()


def instrumentation(command, adb, path, timeout):
    try:
        log = checked(command, timeout=timeout)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        def text(value):
            return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else value or ""
        try:
            path.write_text((text(error.stdout) + "\n" + text(error.stderr))[-(1 << 20):])
            process = subprocess.run(adb + ["shell", "pidof", PACKAGE], capture_output=True, text=True, timeout=10, check=False)
            if not process.stdout.strip():
                package = subprocess.run(adb + ["shell", "cmd", "package", "list", "packages", "-U", PACKAGE],
                                         capture_output=True, text=True, timeout=10, check=False)
                uids = re.findall(r"\buid:(\d+)\b", package.stdout)
                if len(uids) == 1:
                    logcat = subprocess.run(adb + ["logcat", "-d", "--uid=" + uids[0], "-v", "threadtime"],
                                            capture_output=True, text=True, timeout=10, check=False)
                    path.with_name(path.stem + "-logcat-uid-" + uids[0] + ".txt").write_text(logcat.stdout[-(1 << 20):])
            for pid in process.stdout.split():
                if not pid.isascii() or not pid.isdecimal(): continue
                logcat = subprocess.run(adb + ["logcat", "-d", "--pid=" + pid, "-v", "threadtime"],
                                        capture_output=True, text=True, timeout=10, check=False)
                path.with_name(path.stem + "-logcat-" + pid + ".txt").write_text(logcat.stdout[-(1 << 20):])
            for kind in ("exit-info", "processes"):
                state = subprocess.run(adb + ["shell", "dumpsys", "activity", kind, PACKAGE],
                                       capture_output=True, text=True, timeout=10, check=False)
                path.with_name(path.stem + "-" + kind + ".txt").write_text((state.stdout + state.stderr)[-(1 << 20):])
        except (OSError, subprocess.SubprocessError):
            pass
        finally:
            # Killing the adb client alone does not stop Android instrumentation.
            try:
                subprocess.run(adb + ["shell", "am", "force-stop", PACKAGE], capture_output=True, timeout=10, check=False)
            except (OSError, subprocess.SubprocessError):
                pass
        raise
    path.write_text(log + "\n")
    return log


def artifact(path, output):
    with path.open("rb") as stream:
        checksum = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"path": str(path.relative_to(output)), "sha256": checksum}


def validator():
    spec = importlib.util.spec_from_file_location("awg_validator", Path(__file__).with_name("validate-android-awg-interop.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


WIRE = validator().WIRE
SCENARIOS = ("payload", "small-datagrams", *WIRE.TIMER_SCENARIOS, *WIRE.COOKIE_SCENARIOS, *WIRE.NEGATIVE_SCENARIOS)


@contextmanager
def emulator_vpn_authorization(adb):
    # API 35 ignores a package-mode grant for VpnService pre-consent; the uid mode is what counts.
    previous = checked(adb + ["shell", "cmd", "appops", "get", "--uid", PACKAGE, "ACTIVATE_VPN"])
    modes = re.findall(r"^\s*(?:Uid mode: )?ACTIVATE_VPN: ([a-z]+)(?:;|$)", previous, re.MULTILINE)
    if len(modes) == 1 and modes[0] in {"allow", "ignore", "deny", "default", "foreground"}:
        mode = modes[0]
    elif not modes and ("No operations." in previous or previous.startswith("Uid mode:")):
        mode = "default"
    else:
        raise ValueError("cannot determine prior emulator VPN authorization")
    try:
        # Scoped emulator authorization; instrumentation verifies VpnService.prepare
        # and the actual OS protect(fd) result. This host never establishes a TUN.
        checked(adb + ["shell", "cmd", "appops", "set", "--uid", PACKAGE, "ACTIVATE_VPN", "allow"])
        yield
    finally:
        checked(adb + ["shell", "cmd", "appops", "set", "--uid", PACKAGE, "ACTIVATE_VPN", mode])


@contextmanager
def emulator_single_underlay(adb):
    """Keep only the emulator Wi-Fi underlay during the native runs.

    The emulated cellular network appears and disappears while the Wi-Fi network
    loses validation. Each default-network switch fails in-flight UDP sends with
    ENETUNREACH, which the lockstep UDP series reports as a lost datagram.
    """
    previous = checked(adb + ["shell", "settings", "get", "global", "mobile_data"])
    if previous not in {"0", "1"}:
        raise ValueError("cannot determine prior emulator mobile data state")
    try:
        checked(adb + ["shell", "svc", "data", "disable"])
        yield
    finally:
        if previous == "1":
            checked(adb + ["shell", "svc", "data", "enable"])


def await_idle_guest(adb, timeout=1200, poll=10, settle=300):
    """Wait for the post-boot CPU storm to end before the native runs.

    A fresh emulator starts quiet, then reports a one-minute load average above
    60 for several minutes after boot completes. The starved guest drops UDP
    datagrams before any socket receives them, which the lockstep UDP series
    reports as a lost datagram.
    """
    cores = int(checked(adb + ["shell", "nproc"]))
    deadline = time.monotonic() + timeout
    while True:
        booted = checked(adb + ["shell", "getprop", "sys.boot_completed"]) == "1"
        uptime = float(checked(adb + ["shell", "cat", "/proc/uptime"]).split()[0])
        load = float(checked(adb + ["shell", "cat", "/proc/loadavg"]).split()[0])
        if booted and uptime >= settle and load <= cores:
            state = checked(adb + ["shell", "am", "get-started-user-state", "0"])
            if state != "RUNNING_UNLOCKED":
                raise ValueError(f"emulator user is {state}; use an unlocked lease or a temporary AVD with clean credential storage")
            return load
        if time.monotonic() >= deadline:
            raise ValueError(f"emulator stayed busy: one-minute load {load} on {cores} cores")
        time.sleep(poll)


def build(args):
    if not args.peer_source:
        raise ValueError("--peer-source must name the canonical v3.1.20260828 Git checkout")
    revision = checked(["git", "-C", str(args.peer_source), "rev-parse", "HEAD"])
    if revision != PEER_COMMIT:
        raise ValueError("independent peer checkout is not the exact baseline commit")
    source_sha = checked(["git", "rev-parse", "HEAD"])
    status = checked(["git", "status", "--porcelain", "--untracked-files=all"])
    if status and not args.candidate_patch:
        raise ValueError("commit implementation before exact-source acceptance")
    if any(line[:2] not in (" M", "M ", "MM") for line in status.splitlines()):
        raise ValueError("a candidate patch may only modify tracked files")
    output = args.output_dir
    output.mkdir(parents=True, exist_ok=False)
    identity = {"source_sha": source_sha, "candidate_patch": None}
    if status:
        # Evidence-first: the uncommitted change is archived and digested, so the evidence names HEAD + patch.
        patch = output / "candidate.patch"
        patch.write_bytes(subprocess.check_output(["git", "diff", "--binary", "HEAD"], cwd=ROOT))
        identity["candidate_patch"] = artifact(patch, output)
    archive = subprocess.check_output(["git", "-C", str(args.peer_source), "archive", PEER_COMMIT])
    peer_source = output / "upstream"
    peer_source.mkdir()
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        tar.extractall(peer_source, filter="data")
    peer = output / "peer"
    peer.mkdir()
    for fixture in (ROOT / "scripts/fixtures/amneziawg-peer").glob("*.go"):
        shutil.copyfile(fixture, peer / fixture.name)
    module = (peer_source / "go.mod").read_text()
    module = re.sub(r"^module\s+\S+", "module ripdpi.test/amneziawg-android-peer", module, count=1)
    module += f"\nrequire github.com/amnezia-vpn/amneziawg-go/v3 v3.1.20260828\nreplace github.com/amnezia-vpn/amneziawg-go/v3 => {peer_source}\n"
    (peer / "go.mod").write_text(module)
    shutil.copyfile(peer_source / "go.sum", peer / "go.sum")
    go = ["mise", "exec", "go@1.27.3", "--", "go"]
    go_env = dict(os.environ, GOTOOLCHAIN="local", GOMAXPROCS="2")
    if not checked(go + ["version"], env=go_env).startswith(f"go version {GO_VERSION} "):
        raise ValueError("pinned Go compiler unavailable")
    checked(go + ["build", "-mod=readonly", "-p", "2", "-trimpath", "-o", str(output / "peer-binary"), "."], cwd=peer, env=go_env)
    sdk = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk"))
    ndk = sdk / "ndk/29.0.14206865"
    compiler = ndk / "toolchains/llvm/prebuilt/darwin-x86_64/bin"
    env = dict(os.environ)
    for name in ("SDKROOT", "MACOSX_DEPLOYMENT_TARGET", "CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS", "RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS"):
        env.pop(name, None)
    env.update(ANDROID_NDK_HOME=str(ndk), CARGO_TARGET_DIR=str(output / "rust-target"),
               CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=str(compiler / "aarch64-linux-android27-clang"),
               CC_aarch64_linux_android=str(compiler / "aarch64-linux-android27-clang"),
               CXX_aarch64_linux_android=str(compiler / "aarch64-linux-android27-clang++"),
               AR_aarch64_linux_android=str(compiler / "llvm-ar"),
               CMAKE=str(sdk / "cmake/3.31.6/bin/cmake"), BORING_BSSL_RUST_CPPLIB="c++_static")
    checked(["cargo", "build", "--locked", "-p", "ripdpi-amneziawg-android", "--target", "aarch64-linux-android",
             "--profile", "android-jni-dev", "--jobs", "2"], cwd=RUST, env=env, timeout=1800)
    library = output / "libripdpi-amneziawg.so"
    shutil.copyfile(output / "rust-target/aarch64-linux-android/android-jni-dev/libripdpi_amneziawg_android.so", library)
    jni = ROOT / "testing/awg-interop/build/generated/jniLibs/arm64-v8a"
    jni.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(library, jni / library.name)
    # This host depends only on the genuine AWG JNI artifact built above. Other app runtimes are not packaged.
    checked([str(ROOT / "gradlew"), ":testing:awg-interop:assembleDebugAndroidTest", "--max-workers=4",
             "-Pripdpi.localNativeAbis=arm64-v8a", "-Pripdpi.skipNativeBuild=true"])
    apks = list((ROOT / "testing/awg-interop/build/outputs/apk/androidTest/debug").glob("*.apk"))
    if len(apks) != 1:
        raise ValueError("expected one instrumented test host APK")
    shutil.copyfile(apks[0], output / "test.apk")
    (output / "identity.json").write_text(json.dumps(identity))


def device(args):
    serial = os.environ.get("ANDROID_SERIAL")
    if not serial or not serial.startswith("emulator-"):
        raise ValueError("a leased emulator ANDROID_SERIAL is required")
    adb = ["adb", "-s", serial]
    if checked(adb + ["shell", "getprop", "ro.kernel.qemu"]) != "1":
        raise ValueError("selected device is not an emulator")
    output = args.output_dir
    checked(adb + ["install", "-r", str(output / "test.apk")], timeout=120)
    with emulator_vpn_authorization(adb), emulator_single_underlay(adb):
        await_idle_guest(adb)
        if args.matrix:
            cases = [(profile, scenario) for profile in PROFILES for scenario in ("payload", "small-datagrams")]
            cases += [("v3-cpa-trailers", scenario) for scenario in SCENARIOS if scenario not in ("payload", "small-datagrams")]
            matrix = {"version": "android_awg_native_matrix_v1", "nonce": args.nonce,
                      "source_sha": json.loads((output / "identity.json").read_text())["source_sha"],
                      "candidate_patch": json.loads((output / "identity.json").read_text())["candidate_patch"],
                      "artifacts": {"native_library": artifact(output / "libripdpi-amneziawg.so", output),
                                    "test_apk": artifact(output / "test.apk", output), "peer_binary": artifact(output / "peer-binary", output)},
                      "cases": [], "expected_cases": len(cases)}
            results = matrix["cases"]
            for profile, scenario in cases:
                case = output / "cases" / f"{profile}-{scenario}"
                case.mkdir(parents=True, exist_ok=False)
                for name in ("test.apk", "libripdpi-amneziawg.so", "peer-binary", "identity.json", "candidate.patch"):
                    if (output / name).exists(): os.link(output / name, case / name)
                selected = argparse.Namespace(**{**vars(args), "profile": profile, "scenario": scenario, "output_dir": case})
                device_cycles(selected, adb, case)
                results.append({"profile": profile, "scenario": scenario, "evidence": artifact(case / "evidence.json", output)})
                (output / "matrix.json").write_text(json.dumps(matrix, indent=2) + "\n")
            validator().validate_matrix(output / "matrix.json", args.nonce, matrix["source_sha"])
        else:
            device_cycles(args, adb, output)


def profile_arguments(args):
    tcp_bytes, udp_series = PROFILES[args.profile]
    values = ["-e", "awgScenario", args.scenario, "-e", "awgProfile", args.profile, "-e", "awgTcpBytes", str(tcp_bytes), "-e", "awgUdpSeries", str(udp_series)]
    return values + (["-e", "awgHeaderKey", HEADER_KEY] if args.profile not in ("awg-3.1-ranges-v1", "v3-aligned") else [])


def await_peer(peer):
    with selectors.DefaultSelector() as selector:
        selector.register(peer.stdout, selectors.EVENT_READ)
        if not selector.select(timeout=15):
            raise TimeoutError("independent peer startup timeout")
        ready = json.loads(peer.stdout.readline())
    host, port = ready["endpoint"].rsplit(":", 1)
    if host != "127.0.0.1" or not 0 < int(port) < 65536:
        raise ValueError("peer must bind IPv4 loopback")
    return ready, port


def stop_peer(peer):
    if peer.poll() is None:
        peer.terminate()
        try:
            peer.wait(timeout=5)
        except subprocess.TimeoutExpired:
            peer.kill()
            peer.wait(timeout=5)


def negative_control(args, adb, output):
    """The same profile with a wrong header protection key must never authenticate."""
    env = dict(os.environ, RIPDPI_AWG_INTEROP_MTU="1280", RIPDPI_AWG_PROFILE=args.profile)
    with subprocess.Popen([str(output / "peer-binary")], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, text=True, env=env) as peer:
        try:
            ready, port = await_peer(peer)
            path = output / "instrumentation-negative-control.txt"
            log = instrumentation(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", NEGATIVE_METHOD,
                                 "-e", "awgPeerPort", port, "-e", "awgPublicKey", ready["public_key"], "-e", "awgMtu", "1280",
                                 "-e", "awgProfile", args.profile, "-e", "awgHeaderKey", WRONG_HEADER_KEY,
                                 "-e", "awgNonce", args.nonce, RUNNER], adb, path, timeout=90)
            peer.stdin.write("stop\n")
            peer.stdin.flush()
            # No exchange completes, so the peer exits at its session deadline.
            code = peer.wait(timeout=150)
            error = peer.stderr.read(4096).strip()
        finally:
            stop_peer(peer)
    records = [json.loads(line.removeprefix("INSTRUMENTATION_STATUS: awgNegativeEvidence="))
               for line in log.splitlines() if line.startswith("INSTRUMENTATION_STATUS: awgNegativeEvidence=")]
    if len(records) != 1:
        raise ValueError("negative control did not produce exactly one record")
    return {**records[0], "instrumentation": artifact(path, output), "peer_exit": code, "peer_error": error}


def device_cycles(args, adb, output):
    cycles, observations = [], []
    feature = args.scenario != "payload"
    expected_cycles = 1 if feature else 4
    method = FEATURE_METHOD if feature else METHOD
    for mtu in (1280, 1420):
        env = dict(os.environ, RIPDPI_AWG_INTEROP_MTU=str(mtu), RIPDPI_AWG_INTEROP_DNS="0" if feature else "1", RIPDPI_AWG_INTEROP_CYCLES=str(expected_cycles),
                   RIPDPI_AWG_SCENARIO=args.scenario,
                   RIPDPI_AWG_PROFILE=args.profile)
        env.pop("RIPDPI_AWG_UDP_SERIES", None)
        if not feature and PROFILES[args.profile][1]:
            env["RIPDPI_AWG_UDP_SERIES"] = str(PROFILES[args.profile][1])
        with subprocess.Popen([str(output / "peer-binary")], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, text=True, env=env) as peer:
            try:
                ready, port = await_peer(peer)
                # Retain this live peer and endpoint through baseline + three restarts.
                for cycle in range(expected_cycles):
                    command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", method,
                                     "-e", "awgPeerPort", port, "-e", "awgPublicKey", ready["public_key"],
                                     "-e", "awgMtu", str(mtu), "-e", "awgCycle", str(cycle),
                                     "-e", "awgNonce", args.nonce] + profile_arguments(args) + [RUNNER]
                    path = output / f"instrumentation-{mtu}-{cycle}.txt"
                    log = instrumentation(command, adb, path, timeout=240)
                    records = [json.loads(line.removeprefix("INSTRUMENTATION_STATUS: awgEvidence="))
                               for line in log.splitlines() if line.startswith("INSTRUMENTATION_STATUS: awgEvidence=")]
                    if len(records) != 1:
                        raise ValueError("native instrumentation did not produce exactly one measurement record")
                    records[0]["instrumentation"] = artifact(path, output)
                    cycles.append(records[0])
                completion, diagnostics = peer.communicate(input="stop\n", timeout=10)
                if peer.returncode != 0:
                    raise ValueError(f"independent peer exchanges failed: {diagnostics[:4096]}")
                if len(completion) > 1 << 20:
                    raise ValueError("peer observation exceeds the bounded report size")
                lines = [line for line in completion.splitlines() if line.startswith('{"wire_observation"')]
                if len(lines) != 1:
                    raise ValueError("independent peer did not report one wire observation")
                path = output / f"peer-observation-{mtu}.json"
                path.write_text(lines[0] + "\n")
                observations.append({"mtu": mtu, **json.loads(lines[0])["wire_observation"], "artifact": artifact(path, output)})
            finally:
                stop_peer(peer)
    negative = negative_control(args, adb, output) if not feature and args.profile == "amneziavpn-5x" else None
    data = {"version": "android_awg_native_interop_v3", "scenario": args.scenario, "nonce": args.nonce, "profile": args.profile,
            **json.loads((output / "identity.json").read_text()),
            "scope": "native_core", "device_class": "emulator", "abi": "arm64-v8a", "api": cycles[0]["api"],
            "peer": {"version": "v3.1.20260828", "commit": PEER_COMMIT, "go": GO_VERSION}, "cycles": cycles,
            "wire_observations": observations, "negative_control": negative,
            "artifacts": {"native_library": artifact(output / "libripdpi-amneziawg.so", output),
                          "test_apk": artifact(output / "test.apk", output), "peer_binary": artifact(output / "peer-binary", output)}}
    evidence = output / "evidence.json"
    evidence.write_text(json.dumps(data, indent=2) + "\n")
    validator().validate(evidence, args.nonce, data["source_sha"])
    print(f"PASS: scoped emulator/native-core AWG evidence: {evidence}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", required=True, type=lambda value: Path(value).resolve())
    parser.add_argument("--nonce", required=True)
    parser.add_argument("--peer-source", type=Path)
    parser.add_argument("--api", default="32")
    parser.add_argument("--target", default="google_apis", help="installed Android system-image tag")
    parser.add_argument("--avd-profile", default="pixel_6", help="emulator hardware profile of the pool slot")
    parser.add_argument("--temporary-reason", help="use a temporary AVD instead of a pool slot, for this reason")
    parser.add_argument("--profile", choices=sorted(PROFILES), default="awg-3.1-ranges-v1", help="peer and client AWG envelope")
    parser.add_argument("--scenario", choices=SCENARIOS, default="payload")
    parser.add_argument("--matrix", action="store_true", help="run every payload/profile and supported feature case under one pool lease")
    parser.add_argument("--candidate-patch", action="store_true",
                        help="build HEAD plus uncommitted tracked changes and archive them as candidate.patch")
    parser.add_argument("--device-phase", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.scenario not in ("payload", "small-datagrams") and args.profile != "v3-cpa-trailers":
        parser.error("controlled scenarios require v3-cpa-trailers")
    if args.matrix and (args.profile != "awg-3.1-ranges-v1" or args.scenario != "payload"):
        parser.error("--matrix selects its own complete profile/scenario set")
    if not re.fullmatch(r"[a-zA-Z0-9_-]{8,128}", args.nonce):
        parser.error("nonce must be 8..128 ASCII alphanumeric, underscore or hyphen characters")
    if os.environ.get("BUILD_GATE_HELD") != "1":
        parser.error("run through build-gate -- python3 scripts/tests/run-android-awg-interop.py ...")
    try:
        if args.device_phase:
            device(args)
        else:
            build(args)
            checked([sys.executable, str(ROOT / "scripts/ci/android-avd-pool.py"), "run", "--api", args.api,
                     "--arch", "arm64-v8a", "--target", args.target, "--profile", args.avd_profile] +
                    (["--temporary", "--reason", args.temporary_reason] if args.temporary_reason else []) + ["--",
                     sys.executable, str(Path(__file__).resolve()), "--device-phase", "--output-dir", str(args.output_dir),
                     "--nonce", args.nonce, "--api", args.api, "--profile", args.profile, "--scenario", args.scenario] +
                    (["--matrix"] if args.matrix else []), timeout=5400)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        detail = error.stderr if isinstance(error, subprocess.CalledProcessError) else str(error)
        print(f"FAIL: {detail}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
