#!/usr/bin/env python3
"""Validate invocation-bound Android AWG native-core evidence; never grants TUN acceptance."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile

VERSION = "android_awg_native_interop_v1"
PEER_COMMIT = "2e3f7d122ca8ef61e403fddc48a9db8fccd95dbf"
METHOD = "com.poyka.ripdpi.testing.awginterop.AwgNativeInteropTest#nativeCoreExchangesDualStackPayloads"
SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def artifact(base, record):
    path = (base / record["path"]).resolve()
    require(path.is_relative_to(base.resolve()) and path.is_file(), "artifact path escapes or is absent")
    require(SHA256.fullmatch(record["sha256"]) and digest(path) == record["sha256"], "artifact digest mismatch")
    return path


def validate(path, nonce, source_sha):
    data = json.loads(Path(path).read_text())
    require(data["version"] == VERSION and data["nonce"] == nonce, "version or invocation mismatch")
    require(data["source_sha"] == source_sha and re.fullmatch(r"[0-9a-f]{40}", source_sha), "source mismatch")
    require(data["scope"] == "native_core" and data["device_class"] == "emulator", "unsupported evidence scope")
    require(data["abi"] == "arm64-v8a" and type(data["api"]) is int and data["api"] >= 27, "invalid Android identity")
    require(data["peer"] == {"version": "v0.2.12", "commit": PEER_COMMIT, "go": "go1.27.1"}, "peer identity mismatch")
    base = Path(path).parent
    require(set(data["artifacts"]) == {"native_library", "test_apk", "peer_binary"}, "missing native artifact identity")
    for record in data["artifacts"].values():
        artifact(base, record)
    with zipfile.ZipFile(artifact(base, data["artifacts"]["test_apk"])) as apk:
        names = [name for name in apk.namelist() if name.endswith("/libripdpi-amneziawg.so")]
        require(names == ["lib/arm64-v8a/libripdpi-amneziawg.so"], "APK must package exactly the selected arm64 AWG library")
        require(hashlib.sha256(apk.read(names[0])).hexdigest() == data["artifacts"]["native_library"]["sha256"], "APK packages a different native library")
    cycles = data["cycles"]
    require(len(cycles) == 6, "six complete MTU/reconnect observations required")
    require({(c["mtu"], c["cycle"]) for c in cycles} == {(m, c) for m in (1280, 1420) for c in (1, 2, 3)}, "incomplete MTU/cycle matrix")
    for cycle in cycles:
        require(cycle["nonce"] == nonce and cycle["abi"] == data["abi"] and cycle["api"] == data["api"], "cycle identity mismatch")
        protection = cycle["socket_protection"]
        require(set(protection) == {"attempted", "successful"} and
                all(type(value) is int and value == 1 for value in protection.values()),
                "real Android socket protection absent")
        log = artifact(base, cycle["instrumentation"]).read_text()
        observed = [json.loads(line.removeprefix("INSTRUMENTATION_STATUS: awgEvidence="))
                    for line in log.splitlines() if line.startswith("INSTRUMENTATION_STATUS: awgEvidence=")]
        expected = {k: v for k, v in cycle.items() if k != "instrumentation"}
        require(observed == [expected], "measurement record differs from instrumentation artifact")
        fields, started, completed, final = {}, [], [], []
        for line in log.splitlines():
            if line.startswith("INSTRUMENTATION_STATUS: "):
                key, separator, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
                if separator:
                    fields[key] = value
            elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
                code = int(line.split(":", 1)[1])
                identity = fields.get("class", "") + "#" + fields.get("test", "")
                require(code in (0, 1, 2), "instrumentation failed or skipped")
                if code == 1:
                    started.append(identity)
                elif code == 0:
                    completed.append(identity)
                fields = {}
            elif line.startswith("INSTRUMENTATION_CODE: "):
                final.append(int(line.split(":", 1)[1]))
        require(started == [METHOD] and completed == [METHOD] and final == [-1], "wrong, missing or duplicate instrumentation method")
        measurements = cycle["measurements"]
        require(len(measurements) == 8, "complete TCP, UDP and DNS measurements required")
        for family in (4, 6):
            values = [v for v in measurements if v["family"] == family]
            require(sorted(v["protocol"] for v in values) == ["dns", "tcp", "udp", "udp"], "missing protocol/family observation")
            for value in values:
                require(type(value["sent_bytes"]) is int and value["sent_bytes"] > 0, "no sent bytes")
                require(type(value["received_bytes"]) is int and value["received_bytes"] > 0, "no received bytes")
                require(SHA256.fullmatch(value["sent_sha256"]) and SHA256.fullmatch(value["received_sha256"]), "invalid payload identity")
                if value["protocol"] == "dns":
                    require(value["answer_type"] == (1 if family == 4 else 28) and value["transaction_id"] == 0x4321, "DNS response identity mismatch")
                    require(value["received_bytes"] > value["sent_bytes"], "DNS answer absent")
                else:
                    require(value["sent_bytes"] == value["received_bytes"] and value["sent_sha256"] == value["received_sha256"], "payload mismatch")
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--nonce", required=True)
    parser.add_argument("--source-sha", required=True)
    args = parser.parse_args()
    try:
        validate(args.evidence, args.nonce, args.source_sha)
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print("PASS: Android arm64 emulator native-core dual-stack interop; no Android TUN or physical-device claim")
    return 0


if __name__ == "__main__":
    sys.exit(main())
