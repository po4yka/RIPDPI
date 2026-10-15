#!/usr/bin/env python3
"""Run real AWG JNI interop on a leased arm64 emulator. Invoke through build-gate."""

import argparse
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

ROOT = Path(__file__).resolve().parents[2]
RUST = ROOT / "native/rust"
PEER_COMMIT = "2e3f7d122ca8ef61e403fddc48a9db8fccd95dbf"
METHOD = "com.poyka.ripdpi.testing.awginterop.AwgNativeInteropTest#nativeCoreExchangesDualStackPayloads"
PACKAGE = "com.poyka.ripdpi.testing.awginterop.test"
GO_VERSION = "go1.27.1"


def checked(command, *, cwd=ROOT, env=None, timeout=900):
    return subprocess.run(command, cwd=cwd, env=env, check=True, timeout=timeout, text=True, capture_output=True).stdout.strip()


def artifact(path, output):
    with path.open("rb") as stream:
        checksum = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"path": str(path.relative_to(output)), "sha256": checksum}


def validator():
    spec = importlib.util.spec_from_file_location("awg_validator", Path(__file__).with_name("validate-android-awg-interop.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@contextmanager
def emulator_vpn_authorization(adb):
    previous = checked(adb + ["shell", "cmd", "appops", "get", PACKAGE, "ACTIVATE_VPN"])
    modes = re.findall(r"^\s*ACTIVATE_VPN: ([a-z]+)(?:;|$)", previous, re.MULTILINE)
    if len(modes) == 1 and modes[0] in {"allow", "ignore", "deny", "default", "foreground"}:
        mode = modes[0]
    elif not modes and ("No operations." in previous or previous.startswith("Uid mode:")):
        mode = "default"
    else:
        raise ValueError("cannot determine prior emulator VPN authorization")
    try:
        # Scoped emulator authorization; instrumentation verifies VpnService.prepare
        # and the actual OS protect(fd) result. This host never establishes a TUN.
        checked(adb + ["shell", "cmd", "appops", "set", PACKAGE, "ACTIVATE_VPN", "allow"])
        yield
    finally:
        checked(adb + ["shell", "cmd", "appops", "set", PACKAGE, "ACTIVATE_VPN", mode])


def build(args):
    if not args.peer_source:
        raise ValueError("--peer-source must name the canonical v0.2.12 Git checkout")
    revision = checked(["git", "-C", str(args.peer_source), "rev-parse", "HEAD"])
    if revision != PEER_COMMIT:
        raise ValueError("independent peer checkout is not the exact baseline commit")
    source_sha = checked(["git", "rev-parse", "HEAD"])
    if checked(["git", "status", "--porcelain", "--untracked-files=all"]):
        raise ValueError("commit implementation before exact-source acceptance")
    output = args.output_dir
    output.mkdir(parents=True, exist_ok=False)
    archive = subprocess.check_output(["git", "-C", str(args.peer_source), "archive", PEER_COMMIT])
    peer_source = output / "upstream"
    peer_source.mkdir()
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        tar.extractall(peer_source, filter="data")
    peer = output / "peer"
    peer.mkdir()
    shutil.copyfile(ROOT / "scripts/fixtures/amneziawg-peer/main.go", peer / "main.go")
    module = (peer_source / "go.mod").read_text()
    module = re.sub(r"^module\s+\S+", "module ripdpi.test/amneziawg-android-peer", module, count=1)
    module += f"\nrequire github.com/amnezia-vpn/amneziawg-go v0.2.12\nreplace github.com/amnezia-vpn/amneziawg-go => {peer_source}\n"
    (peer / "go.mod").write_text(module)
    shutil.copyfile(peer_source / "go.sum", peer / "go.sum")
    go = ["mise", "exec", "go@1.27.1", "--", "go"]
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
    (output / "identity.json").write_text(json.dumps({"source_sha": source_sha}))


def device(args):
    serial = os.environ.get("ANDROID_SERIAL")
    if not serial or not serial.startswith("emulator-"):
        raise ValueError("a leased emulator ANDROID_SERIAL is required")
    adb = ["adb", "-s", serial]
    if checked(adb + ["shell", "getprop", "ro.kernel.qemu"]) != "1":
        raise ValueError("selected device is not an emulator")
    output = args.output_dir
    checked(adb + ["install", "-r", str(output / "test.apk")], timeout=120)
    with emulator_vpn_authorization(adb):
        device_cycles(args, adb, output)


def device_cycles(args, adb, output):
    cycles = []
    for mtu in (1280, 1420):
        env = dict(os.environ, RIPDPI_AWG_INTEROP_MTU=str(mtu), RIPDPI_AWG_INTEROP_DNS="1", RIPDPI_AWG_INTEROP_CYCLES="4")
        with subprocess.Popen([str(output / "peer-binary")], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, text=True, env=env) as peer:
            try:
                with selectors.DefaultSelector() as selector:
                    selector.register(peer.stdout, selectors.EVENT_READ)
                    if not selector.select(timeout=15):
                        raise TimeoutError("independent peer startup timeout")
                    ready = json.loads(peer.stdout.readline())
                host, port = ready["endpoint"].rsplit(":", 1)
                if host != "127.0.0.1" or not 0 < int(port) < 65536:
                    raise ValueError("peer must bind IPv4 loopback")
                # Retain this live peer and endpoint through baseline + three restarts.
                for cycle in range(4):
                    command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", METHOD,
                                     "-e", "awgPeerPort", port, "-e", "awgPublicKey", ready["public_key"],
                                     "-e", "awgMtu", str(mtu), "-e", "awgCycle", str(cycle),
                                     "-e", "awgNonce", args.nonce, f"{PACKAGE}/androidx.test.runner.AndroidJUnitRunner"]
                    log = checked(command, timeout=45)
                    path = output / f"instrumentation-{mtu}-{cycle}.txt"
                    path.write_text(log + "\n")
                    records = [json.loads(line.removeprefix("INSTRUMENTATION_STATUS: awgEvidence="))
                               for line in log.splitlines() if line.startswith("INSTRUMENTATION_STATUS: awgEvidence=")]
                    if len(records) != 1:
                        raise ValueError("native instrumentation did not produce exactly one measurement record")
                    records[0]["instrumentation"] = artifact(path, output)
                    cycles.append(records[0])
                peer.stdin.write("stop\n")
                peer.stdin.flush()
                if peer.wait(timeout=10) != 0:
                    raise ValueError(f"independent peer exchanges failed: {peer.stderr.read(4096)}")
            finally:
                if peer.poll() is None:
                    peer.terminate()
                    try:
                        peer.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        peer.kill()
                        peer.wait(timeout=5)
    data = {"version": "android_awg_native_interop_v1", "nonce": args.nonce,
            "source_sha": json.loads((output / "identity.json").read_text())["source_sha"],
            "scope": "native_core", "device_class": "emulator", "abi": "arm64-v8a", "api": cycles[0]["api"],
            "peer": {"version": "v0.2.12", "commit": PEER_COMMIT, "go": GO_VERSION}, "cycles": cycles,
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
    parser.add_argument("--device-phase", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
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
                     "--arch", "arm64-v8a", "--target", args.target, "--profile", "pixel_6", "--",
                     sys.executable, str(Path(__file__).resolve()), "--device-phase", "--output-dir", str(args.output_dir),
                     "--nonce", args.nonce, "--api", args.api], timeout=900)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        detail = error.stderr if isinstance(error, subprocess.CalledProcessError) else str(error)
        print(f"FAIL: {detail}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
