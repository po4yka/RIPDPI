#!/usr/bin/env python3
"""Validate invocation-bound Android AWG native-core evidence; never grants TUN acceptance."""

import argparse
import importlib.util
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile

WIRE_SPEC = importlib.util.spec_from_file_location("awg_wire_validation", Path(__file__).with_name("awg_wire_validation.py"))
WIRE = importlib.util.module_from_spec(WIRE_SPEC)
WIRE_SPEC.loader.exec_module(WIRE)
validate_wire_observation = WIRE.validate_wire_observation

VERSION = "android_awg_native_interop_v3"
PEER_COMMIT = "b5928efb6ca19f0153958460c3d141f04abc5c2e"
METHOD = "com.poyka.ripdpi.testing.awginterop.AwgNativeInteropTest#nativeCoreExchangesDualStackPayloads"
FEATURE_METHOD = "com.poyka.ripdpi.testing.awginterop.AwgNativeInteropTest#nativeCoreExercisesFeatureScenario"
NEGATIVE_METHOD = "com.poyka.ripdpi.testing.awginterop.AwgNativeInteropTest#nativeCoreRefusesMismatchedHeaderProtection"
# Per profile: minimum TCP bytes and lockstep UDP datagrams per family and session.
PROFILES = {"awg-3.1-ranges-v1": (1, 0), "amneziavpn-5x": (4 << 20, 512)}
PROFILES.update({name: (1, 0) for name in ("v3-cpa", "v3-trailers", "v3-cpa-trailers", "v3-aligned", "v3-fixed-headers")})
SCENARIOS = ("payload", "small-datagrams", *WIRE.TIMER_SCENARIOS, *WIRE.COOKIE_SCENARIOS, *WIRE.NEGATIVE_SCENARIOS)
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


def require_single_pass(log, method):
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
    require(started == [method] and completed == [method] and final == [-1], "wrong, missing or duplicate instrumentation method")


def records(log, key):
    prefix = f"INSTRUMENTATION_STATUS: {key}="
    return [json.loads(line.removeprefix(prefix)) for line in log.splitlines() if line.startswith(prefix)]


def validate_observations(base, data):
    scenario = data["scenario"]
    expected_cycles = 4 if scenario == "payload" else 1
    observations = data["wire_observations"]
    require(sorted(value["mtu"] for value in observations) == [1280, 1420], "peer wire observation missing for an MTU")
    for value in observations:
        recorded = json.loads(artifact(base, value["artifact"]).read_text())["wire_observation"]
        require(recorded == {k: v for k, v in value.items() if k not in ("mtu", "artifact")}, "wire observation differs from peer artifact")
        require(value["wire_profile"] == data["profile"] and value["handshakes"] >= expected_cycles, "peer did not observe every session")
        if scenario in WIRE.TIMER_SCENARIOS:
            if scenario == "timer-reject":
                cycle = next(c for c in data["cycles"] if c["mtu"] == value["mtu"])
                recorded = {**recorded, "expired_probe": cycle["feature"]["expired_probe"]}
            WIRE.validate_timer_observation(recorded, value["mtu"], scenario)
            continue
        if scenario in WIRE.COOKIE_SCENARIOS:
            WIRE.validate_cookie_observation(recorded, value["mtu"], scenario)
            continue
        if scenario in WIRE.NEGATIVE_SCENARIOS:
            WIRE.validate_negative_transport(recorded, value["mtu"])
            continue
        evidence = validate_wire_observation(recorded, data["profile"], value["mtu"], expected_cycles, scenario)
        if scenario != "payload": continue
        sent = sum(m["sent_bytes"] for c in data["cycles"] if c["mtu"] == value["mtu"]
                   for m in c["measurements"] if m["protocol"] == "tcp")
        require(sum(int(size)*count for size, count in evidence["ip_lengths"].items()) >= sent,
                "authenticated IP volume is below reported TCP payload")
        if data["profile"] == "amneziavpn-5x":
            require(value["padding"] == [12, 12, 12, 12] and value["headers_in_clear"] == 0, "S1-S4 or header protection not observed")


def validate_negative(base, data, nonce):
    negative = data["negative_control"]
    if data["scenario"] != "payload":
        require(negative is None, "feature evidence cannot replace the payload wrong-key gate")
        return
    if data["profile"] != "amneziavpn-5x":
        require(negative is None, "negative control belongs to the header-protected profile")
        return
    log = artifact(base, negative["instrumentation"]).read_text()
    require_single_pass(log, NEGATIVE_METHOD)
    require(records(log, "awgNegativeEvidence") == [{k: v for k, v in negative.items() if k not in ("instrumentation", "peer_exit", "peer_error")}],
            "negative record differs from instrumentation artifact")
    require(negative["nonce"] == nonce and negative["abi"] == data["abi"] and negative["api"] == data["api"], "negative identity mismatch")
    require(negative["reached_running"] is False and negative["window_ms"] >= 10000, "wrong-key session reached readiness")
    require(type(negative["peer_exit"]) is int and negative["peer_exit"] != 0 and
            negative["peer_error"].startswith("incomplete exchanges: 0/"), "peer completed an exchange with a wrong key")


def validate_feature(cycle, scenario):
    feature = cycle.get("feature", {})
    require(cycle.get("scenario") == scenario and feature.get("scenario") == scenario and feature.get("completed") is True,
            "missing client feature completion")
    elapsed = feature.get("elapsed_ms")
    require(type(elapsed) is int and 0 < elapsed < 60000, "missing bounded client feature window")
    phases = []
    if scenario in WIRE.COOKIE_SCENARIOS:
        phases = ["invalid-mac1", "admission"] + (["exhausted-noise"] if scenario == "cookies-disabled" else ["bad-mac2", "valid-mac2"]) + ["challenge-budget", "recovery"]
    if scenario == "negative-transport": phases = ["corrupt", "wrong-hp", "foreign-source", "replay", "ambiguous", "malformed-ip"]
    require(feature.get("phases") == phases, "client feature phases missing")
    minimum = {"timer-persistent": 6200, "timer-idle": 3700, "timer-retry-exhaust": 7500, "timer-rekey": 2800, "timer-reject": 8500}.get(scenario, 0)
    require(elapsed >= minimum, "client feature window too short")
    if scenario == "timer-reject":
        probe = feature.get("expired_probe", {})
        require(probe.get("no_reply") is True and type(probe.get("wait_ms")) is int and 1000 <= probe["wait_ms"] <= 1500,
                "client expired probe missing")


def validate(path, nonce, source_sha):
    data = json.loads(Path(path).read_text())
    require(data["version"] == VERSION and data["nonce"] == nonce, "version or invocation mismatch")
    require(data["source_sha"] == source_sha and re.fullmatch(r"[0-9a-f]{40}", source_sha), "source mismatch")
    require(data["scope"] == "native_core" and data["device_class"] == "emulator", "unsupported evidence scope")
    require(data["abi"] == "arm64-v8a" and type(data["api"]) is int and data["api"] >= 27, "invalid Android identity")
    require(data["peer"] == {"version": "v3.1.20260828", "commit": PEER_COMMIT, "go": "go1.27.3"}, "peer identity mismatch")
    require(data["profile"] in PROFILES, "unknown AWG profile")
    scenario = data.get("scenario")
    require(scenario in SCENARIOS, "unknown Android scenario")
    feature = scenario != "payload"
    require(scenario in ("payload", "small-datagrams") or data["profile"] == "v3-cpa-trailers", "controlled scenario profile differs")
    tcp_bytes, udp_series = PROFILES[data["profile"]]
    base = Path(path).parent
    require(set(data["artifacts"]) == {"native_library", "test_apk", "peer_binary"}, "missing native artifact identity")
    for record in data["artifacts"].values():
        artifact(base, record)
    if data["candidate_patch"] is not None:
        artifact(base, data["candidate_patch"])
    with zipfile.ZipFile(artifact(base, data["artifacts"]["test_apk"])) as apk:
        names = [name for name in apk.namelist() if name.endswith("/libripdpi-amneziawg.so")]
        require(names == ["lib/arm64-v8a/libripdpi-amneziawg.so"], "APK must package exactly the selected arm64 AWG library")
        require(hashlib.sha256(apk.read(names[0])).hexdigest() == data["artifacts"]["native_library"]["sha256"], "APK packages a different native library")
    cycles = data["cycles"]
    expected_cycles = 1 if feature else 4
    require(len(cycles) == 2 * expected_cycles, "initial session plus three reconnects required at each MTU")
    require({(c["mtu"], c["cycle"]) for c in cycles} == {(m, c) for m in (1280, 1420) for c in range(expected_cycles)}, "incomplete MTU/cycle matrix")
    for mtu in (1280, 1420):
        ports = [cycle["peer_port"] for cycle in cycles if cycle["mtu"] == mtu]
        require(all(type(port) is int and 0 < port < 65536 for port in ports) and len(set(ports)) == 1,
                "reconnects must retain the independent peer endpoint")
    for cycle in cycles:
        require(cycle["nonce"] == nonce and cycle["abi"] == data["abi"] and cycle["api"] == data["api"], "cycle identity mismatch")
        protection = cycle["socket_protection"]
        require(set(protection) == {"attempted", "successful"} and
                all(type(value) is int and value == 1 for value in protection.values()),
                "real Android socket protection absent")
        log = artifact(base, cycle["instrumentation"]).read_text()
        expected = {k: v for k, v in cycle.items() if k != "instrumentation"}
        require(records(log, "awgEvidence") == [expected], "measurement record differs from instrumentation artifact")
        require(cycle["profile"] == data["profile"], "cycle profile mismatch")
        require(cycle.get("scenario", "payload") == scenario, "cycle scenario mismatch")
        require_single_pass(log, FEATURE_METHOD if feature else METHOD)
        if feature:
            require(cycle["measurements"] == [], "feature measurements cannot replace the payload gate")
            validate_feature(cycle, scenario)
            continue
        require("feature" not in cycle, "payload cannot use a feature completion record")
        measurements = cycle["measurements"]
        protocols = ["dns", "tcp", "udp", "udp"] + (["udp_series"] if udp_series else [])
        require(len(measurements) == 2 * len(protocols), "complete TCP, UDP and DNS measurements required")
        for family in (4, 6):
            values = [v for v in measurements if v["family"] == family]
            require(sorted(v["protocol"] for v in values) == protocols, "missing protocol/family observation")
            for value in values:
                require(type(value["sent_bytes"]) is int and value["sent_bytes"] > 0, "no sent bytes")
                require(type(value["received_bytes"]) is int and value["received_bytes"] > 0, "no received bytes")
                require(SHA256.fullmatch(value["sent_sha256"]) and SHA256.fullmatch(value["received_sha256"]), "invalid payload identity")
                if value["protocol"] == "dns":
                    require(value["answer_type"] == (1 if family == 4 else 28) and value["transaction_id"] == 0x4321, "DNS response identity mismatch")
                    require(value["received_bytes"] > value["sent_bytes"], "DNS answer absent")
                else:
                    require(value["sent_bytes"] == value["received_bytes"] and value["sent_sha256"] == value["received_sha256"], "payload mismatch")
                if value["protocol"] == "tcp":
                    require(value["sent_bytes"] >= tcp_bytes, "TCP payload below the profile minimum")
                if value["protocol"] == "udp_series":
                    require(value["datagrams"] >= udp_series, "UDP series below the profile minimum")
    validate_observations(base, data)
    validate_negative(base, data, nonce)
    return data


def validate_matrix(path, nonce, source_sha):
    path = Path(path)
    data = json.loads(path.read_text())
    require(data.get("version") == "android_awg_native_matrix_v1" and data.get("nonce") == nonce and data.get("source_sha") == source_sha,
            "matrix identity mismatch")
    expected = {(profile, scenario) for profile in PROFILES for scenario in ("payload", "small-datagrams")}
    expected.update(("v3-cpa-trailers", scenario) for scenario in SCENARIOS if scenario not in ("payload", "small-datagrams"))
    cases = data.get("cases", [])
    require(data.get("expected_cases") == len(expected) and len(cases) == len(expected) and
            {(c["profile"], c["scenario"]) for c in cases} == expected, "incomplete Android feature matrix")
    require(set(data.get("artifacts", {})) == {"native_library", "test_apk", "peer_binary"}, "matrix artifact identity missing")
    for record in data["artifacts"].values(): artifact(path.parent, record)
    candidate = data.get("candidate_patch")
    if candidate is not None: artifact(path.parent, candidate)
    for case in cases:
        child = validate(artifact(path.parent, case["evidence"]), nonce, source_sha)
        child_candidate = child["candidate_patch"]
        require((child_candidate["sha256"] if child_candidate else None) == (candidate["sha256"] if candidate else None), "matrix mixes candidate patches")
        require((child["profile"], child["scenario"]) == (case["profile"], case["scenario"]), "matrix case identity differs")
        require({key: value["sha256"] for key, value in child["artifacts"].items()} ==
                {key: value["sha256"] for key, value in data["artifacts"].items()}, "matrix mixes native artifacts")
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", action="store_true")
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--nonce", required=True)
    parser.add_argument("--source-sha", required=True)
    args = parser.parse_args()
    try:
        data = (validate_matrix if args.matrix else validate)(args.evidence, args.nonce, args.source_sha)
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    label = "complete profile/feature matrix with dual-stack payload" if args.matrix else "dual-stack payload" if data["scenario"] == "payload" else f"feature scenario {data['scenario']}"
    print(f"PASS: Android arm64 emulator native-core {label}")
    candidate = data.get("candidate_patch")
    if candidate is not None:
        print(f"NOTE: built from {args.source_sha} plus candidate patch sha256 {candidate['sha256']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
