#!/usr/bin/env python3
"""Run pinned kernel AWG payload acceptance only in a task-owned disposable VM.

Build prerequisites and invocation are in docs/native/amneziawg-3x-wire-semantics.md.
The caller supplies the compiled Rust test and Linux echo fixture, with a source receipt.
"""
from __future__ import annotations

import argparse
import base64
import configparser
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import selectors
import subprocess
import tarfile
import tempfile

KERNEL_SHA = "4569c4c67f3a57414969260cafbbd04694fbaae0"
TOOLS_SHA = "ee0f0a9aa34ff0a0da4b3433b9512781cfe02843"
ARCHIVES = {
    "kernel": ("amneziawg-linux-kernel-module", KERNEL_SHA, "6d7608935adbcdd4c6e9fed705951e2ee85311ac212a8e8f1274abcf9b06f83e"),
    "tools": ("amneziawg-tools", TOOLS_SHA, "22438f231d39ea27e4bdc69707ec3103357f3cecc9ad3eecba18a61e5b39c9b9"),
}
PROFILES = ("awg-3.1-ranges-v1", "amneziavpn-5x", "v3-cpa", "v3-trailers", "v3-cpa-trailers", "v3-aligned", "v3-fixed-headers")
SECRETS = {"privatekey", "publickey", "presharedkey", "headerprotectionkey"}
WIRE_SPEC = importlib.util.spec_from_file_location("awg_wire_validation", Path(__file__).with_name("awg_wire_validation.py"))
WIRE = importlib.util.module_from_spec(WIRE_SPEC)
WIRE_SPEC.loader.exec_module(WIRE)



def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def checked(command, *, env=None, output=None, timeout=120):
    command = [str(x) for x in command]
    with subprocess.Popen(command, text=True, env=env, start_new_session=True,
                          stdout=output if output else subprocess.PIPE,
                          stderr=subprocess.STDOUT if output else subprocess.PIPE) as process:
        try:
            stdout, stderr = process.communicate(timeout=timeout)
            if process.returncode:
                raise subprocess.CalledProcessError(process.returncode, command, stdout, stderr)
            return stdout
        finally:
            stop_owned(process)


def verify_vm_binding(owner: dict, machine_id: str, hostname: str):
    actual = hashlib.sha256(machine_id.strip().encode()).hexdigest()
    if owner.get("guest_machine_id_sha256") != actual or owner.get("guest_hostname") != hostname:
        raise ValueError("ownership receipt belongs to a different VM")


def assert_owned_vm(receipt: Path) -> dict:
    if platform.system() != "Linux" or os.geteuid() == 0:
        raise ValueError("run as the ordinary user inside the task-owned Linux VM")
    if not Path("/var/lib/ripdpi-acceptance/packages.tsv").is_file():
        raise ValueError("acceptance VM provisioning receipt is missing")
    checked(["systemd-detect-virt", "--vm"])
    owner = json.loads(receipt.read_text())
    if not owner.get("name", "").startswith("ripdpi-acceptance-") or not owner.get("config_sha256"):
        raise ValueError("VM ownership receipt is invalid")
    verify_vm_binding(owner, Path("/etc/machine-id").read_text(), platform.node())
    if Path("/sys/module/amneziawg").exists():
        raise ValueError("refusing to adopt or unload a pre-existing AWG module")
    return owner


def unpack(kind: str, archive: Path, output: Path) -> Path:
    repository, revision, expected = ARCHIVES[kind]
    if digest(archive) != expected:
        raise ValueError(f"{kind} source digest mismatch")
    with tarfile.open(archive) as source:
        source.extractall(output, filter="data")
    return output / f"{repository}-{revision}" / "src"


def normalized(value: str) -> str:
    if value in ("true", "on", "1"): return "1"
    if value in ("false", "off", "0"): return "0"
    if "-" in value:
        low, high = value.split("-", 1)
        if low == high: return low
    return value


def normalized_private_key(value: str) -> bytes:
    key = bytearray(base64.b64decode(value, validate=True))
    if len(key) != 32: raise ValueError("invalid fixture private key")
    key[0] &= 248
    key[31] = (key[31] & 127) | 64
    return bytes(key)


def verify_readback(expected: str, actual: str) -> dict:
    wanted, readback = configparser.ConfigParser(interpolation=None), configparser.ConfigParser(interpolation=None)
    wanted.read_string(expected)
    readback.read_string(actual)
    result = {}
    for section in wanted.sections():
        for key, value in wanted[section].items():
            if key == "privatekey":
                equal = normalized_private_key(value) == normalized_private_key(readback.get(section, key, fallback=""))
            elif key == "allowedips":
                equal = {x.strip() for x in value.split(',')} == {x.strip() for x in readback.get(section, key, fallback="").split(',')}
            else:
                equal = normalized(value) == normalized(readback.get(section, key, fallback=""))
            if not equal:
                raise ValueError(f"kernel readback mismatch: {section}.{key}")
            if key not in SECRETS:
                result[f"{section}.{key}"] = readback[section][key]
    return result


def stop_owned(process: subprocess.Popen, signal_name="TERM"):
    # The netns process is privileged; kill only the private session created here.
    subprocess.run(["sudo", "-n", "kill", "-" + signal_name, "--", "-" + str(process.pid)],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass
    finally:
        # A leader can exit while a descendant ignores TERM.
        subprocess.run(["sudo", "-n", "kill", "-KILL", "--", "-" + str(process.pid)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        process.wait(timeout=5)


def start_capture(prefix, interface, path, log, filters):
    process = subprocess.Popen(prefix + ["tcpdump", "--immediate-mode", "-U", "-i", interface, "-w", str(path), *filters],
                               stdout=log, stderr=subprocess.PIPE, text=True, start_new_session=True)
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stderr, selectors.EVENT_READ)
            if not selector.select(timeout=5): raise TimeoutError("packet capture readiness deadline")
            line = process.stderr.readline(4096)
            log.write(line)
            if f"listening on {interface}" not in line: raise ValueError("packet capture did not become ready")
        return process
    except BaseException:
        stop_owned(process, "INT")
        raise


def capture_statistics(text):
    captured = re.findall(r"^(\d+) packets? captured$", text, re.MULTILINE)
    dropped = re.findall(r"^(\d+) packets? dropped by kernel$", text, re.MULTILINE)
    if len(captured) != 1 or len(dropped) != 1 or int(captured[0]) == 0 or int(dropped[0]) != 0:
        raise ValueError("capture statistics missing or packets dropped")
    return {"packets": int(captured[0]), "dropped_packets": int(dropped[0])}


def finish_capture(process, log):
    stop_owned(process, "INT")
    if process.returncode != 0: raise ValueError("packet capture failed")
    text = process.stderr.read()
    log.write(text)
    log.flush()
    return capture_statistics(text)


def run_case(args, tools: Path, output: Path, index: int, profile: str, mtu: int, scenario: str) -> dict:
    namespace = f"awg31-{os.getpid()}-{index}"
    prefix = ["sudo", "-n", "ip", "netns", "exec", namespace]
    user = ["setpriv", f"--reuid={os.getuid()}", f"--regid={os.getgid()}", "--clear-groups"]
    env = dict(os.environ, RIPDPI_AWG_PROFILE=profile, RIPDPI_AWG_INTEROP_MTU=str(mtu), RIPDPI_AWG_INTEROP_CYCLES="4", RIPDPI_AWG_SCENARIO=scenario)
    config = checked([args.fixture], env=dict(env, RIPDPI_AWG_KERNEL_MODE="config"))
    conf = output / "fixture.conf"
    conf.write_text(config)
    conf.chmod(0o600)
    created = False
    echo = capture = inner_capture = None
    with (output / "runtime.log").open("w") as runtime_log, (output / "peer.log").open("w") as peer_log, (output / "capture.log").open("w") as capture_log, (output / "inner-capture.log").open("w") as inner_log:
        try:
            checked(["sudo", "-n", "ip", "netns", "add", namespace])
            created = True
            ip = ["sudo", "-n", "ip", "-n", namespace]
            checked(ip + ["link", "set", "lo", "up"])
            checked(ip + ["link", "add", "awg31", "type", "amneziawg"])
            checked(prefix + [tools, "setconf", "awg31", conf])
            readback = verify_readback(config, checked(prefix + [tools, "showconf", "awg31"]))
            checked(ip + ["addr", "add", "10.77.0.1/32", "dev", "awg31"])
            checked(ip + ["-6", "addr", "add", "fd77::1/128", "dev", "awg31", "nodad"])
            checked(ip + ["link", "set", "awg31", "mtu", str(mtu), "up"])
            checked(ip + ["route", "add", "10.77.0.2/32", "dev", "awg31"])
            checked(ip + ["-6", "route", "add", "fd77::2/128", "dev", "awg31"])
            baseline = checked(prefix + [tools, "show", "awg31", "transfer"]).split()
            if len(baseline) != 3 or list(map(int,baseline[1:])) != [0,0]: raise ValueError("kernel transfer baseline is not zero")
            capture = start_capture(prefix,"lo",output/"wire.pcap",capture_log,["udp","port","51820"])
            if scenario == "small-datagrams":
                inner_capture = start_capture(prefix,"awg31",output/"inner.pcap",inner_log,["udp","and","(","port","41002","or","port","41003",")"])
            settings = [f"{key}={value}" for key, value in env.items() if key.startswith("RIPDPI_AWG_")]
            echo = subprocess.Popen(prefix + user + ["env", *settings, "RIPDPI_AWG_KERNEL_MODE=echo", str(args.fixture)],
                                    stdout=subprocess.PIPE, stderr=peer_log, text=True, start_new_session=True)
            with selectors.DefaultSelector() as selector:
                selector.register(echo.stdout, selectors.EVENT_READ)
                if not selector.select(timeout=10): raise TimeoutError("kernel echo readiness deadline")
                ready = json.loads(echo.stdout.readline(4096))
            if not isinstance(ready, dict) or ready.get("endpoint") != "127.0.0.1:51820": raise ValueError("unexpected kernel endpoint")
            # env is passed after sudo, which otherwise removes the test settings.
            settings = [f"{key}={value}" for key, value in dict(env, RIPDPI_AWG_INTEROP_ENDPOINT=ready["endpoint"]).items() if key.startswith("RIPDPI_AWG_")]
            for cycle in range(4):
                runtime_log.write(f"session {cycle}\n")
                runtime_log.flush()
                checked(prefix + user + ["env", *settings, args.test_binary, "--no-capture"], output=runtime_log, timeout=35)
            echo.wait(timeout=5)
            if echo.returncode != 0: raise ValueError("kernel echo failed")
            completed = json.loads(echo.stdout.readline(4096))
            if not isinstance(completed, dict) or completed.get("completed_sessions") != 4: raise ValueError("kernel echo session count mismatch")
            handshake = checked(prefix + [tools, "show", "awg31", "latest-handshakes"]).split()
            transfer = checked(prefix + [tools, "show", "awg31", "transfer"]).split()
            if len(handshake) != 2 or int(handshake[1]) <= 0 or len(transfer) != 3 or min(map(int, transfer[1:])) <= 0:
                raise ValueError("kernel peer has no authenticated traffic")
            result = {"readback": readback, "latest_handshake": int(handshake[1]), "rx_bytes": int(transfer[1]), "tx_bytes": int(transfer[2]), "counter_baseline": {"rx_bytes":0,"tx_bytes":0}}
            result["wire_capture"] = finish_capture(capture,capture_log)
            capture = None
            if inner_capture is not None:
                result["inner_capture"] = finish_capture(inner_capture,inner_log)
                inner_capture = None
                counters = {"rx_bytes":result["rx_bytes"],"tx_bytes":result["tx_bytes"],"last_handshake_time_sec":result["latest_handshake"],"last_handshake_time_nsec":0}
                observed = checked([args.fixture],env=dict(env,RIPDPI_AWG_KERNEL_MODE="observe",
                    RIPDPI_AWG_WIRE_PCAP=str(output/"wire.pcap"),RIPDPI_AWG_INNER_PCAP=str(output/"inner.pcap"),
                    RIPDPI_AWG_KERNEL_COUNTERS=json.dumps(counters)))
                observation = json.loads(observed)["wire_observation"]
                WIRE.validate_wire_observation(observation,profile,mtu,4,"small-datagrams")
                for side in ("wire","inner"):
                    if observation["kernel_capture"][side]["packets"] != result[side+"_capture"]["packets"]:
                        raise ValueError("PCAP record count differs from capture statistics")
                result["observation"] = observation
            return result
        finally:
            errors = []
            for process, signal_name in ((echo, "TERM"), (capture, "INT"), (inner_capture, "INT")):
                if process is not None:
                    try:
                        stop_owned(process, signal_name)
                    except (OSError, subprocess.SubprocessError) as error:
                        errors.append(str(error))
            try:
                if capture is not None and capture.poll() is not None:
                    capture_log.write(capture.stderr.read())
                if inner_capture is not None and inner_capture.poll() is not None:
                    inner_log.write(inner_capture.stderr.read())
            finally:
                try:
                    if created: checked(["sudo", "-n", "ip", "netns", "delete", namespace])
                except (OSError, subprocess.SubprocessError) as error:
                    errors.append(str(error))
                finally:
                    conf.unlink(missing_ok=True)
            if errors: raise RuntimeError("owned resource cleanup failed: " + "; ".join(errors))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", action="append", choices=("payload","small-datagrams"))
    parser.add_argument("--vm-owner", type=Path, required=True)
    parser.add_argument("--identity", type=Path, required=True)
    parser.add_argument("--kernel-archive", type=Path, required=True)
    parser.add_argument("--tools-archive", type=Path, required=True)
    parser.add_argument("--test-binary", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    owner = assert_owned_vm(args.vm_owner)
    args.output_dir.mkdir(parents=True, exist_ok=False)
    report = {"schema_version": 1, "vm": owner["name"], "kernel": platform.release(), "kernel_source_sha": KERNEL_SHA,
              "tools_source_sha": TOOLS_SHA, "identity": json.loads(args.identity.read_text()),
              "test_binary_sha256": digest(args.test_binary), "fixture_sha256": digest(args.fixture), "scenarios":args.scenario or ["payload","small-datagrams"], "cases": []}
    loaded = False
    with tempfile.TemporaryDirectory(prefix="awg31-kernel-") as temporary:
        build = Path(temporary)
        try:
            kernel = unpack("kernel", args.kernel_archive, build)
            tools = unpack("tools", args.tools_archive, build)
            with (args.output_dir / "build.log").open("w") as log:
                checked(["make", "-C", kernel, "-j2", f"KERNELDIR=/lib/modules/{platform.release()}/build"], output=log, timeout=300)
                checked(["make", "-C", tools, "-j2", "WIREGUARD_TOOLS_VERSION=3.1.20260812"], output=log, timeout=120)
            module = kernel / "amneziawg.ko"
            report.update(module_sha256=digest(module), tools_sha256=digest(tools / "wg"))
            for dependency in checked(["modinfo", "-F", "depends", module]).strip().split(','):
                if dependency: checked(["sudo", "-n", "modprobe", dependency])
            checked(["sudo", "-n", "insmod", module])
            loaded = True
            for profile in PROFILES:
                for scenario in args.scenario or ["payload","small-datagrams"]:
                    for mtu in (1280, 1420):
                        case = args.output_dir / f"{profile}-{scenario}-{mtu}"
                        case.mkdir()
                        result = {"profile": profile, "mtu": mtu, "cycles": 4, "scenario": scenario}
                        try:
                            result.update(run_case(args, tools / "wg", case, len(report["cases"]), profile, mtu, scenario), status="passed")
                        except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
                            result.update(status="failed", error=str(error))
                        if (case / "wire.pcap").is_file(): result["pcap_sha256"] = digest(case / "wire.pcap")
                        if (case / "inner.pcap").is_file(): result["inner_pcap_sha256"] = digest(case / "inner.pcap")
                        report["cases"].append(result)
                        (args.output_dir / "results.json").write_text(json.dumps(report, indent=2) + "\n")
                        print(f"{result['status']}: {profile} {scenario} MTU {mtu}", flush=True)
        finally:
            try:
                if loaded: checked(["sudo", "-n", "rmmod", "amneziawg"])
                report["module_cleanup"] = "passed"
            except (OSError, subprocess.SubprocessError) as error:
                report["module_cleanup"] = "failed: " + str(error)
                raise
            finally:
                (args.output_dir / "results.json").write_text(json.dumps(report, indent=2) + "\n")
    if len(report["cases"]) != 14 or any(x["status"] != "passed" for x in report["cases"]):
        raise SystemExit("kernel AWG matrix is incomplete or failed")


if __name__ == "__main__":
    main()
