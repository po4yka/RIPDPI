#!/usr/bin/env python3
"""Run grant/deny/regrant on an explicitly disposable API-37, 16-KB device."""

import argparse
import ipaddress
import json
import re
import subprocess
from pathlib import Path


PACKAGE = "com.poyka.ripdpi"
PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
TEST_CLASS = "com.poyka.ripdpi.target37.LocalNetworkRuntimeTest"
TEST_METHOD = "tcpUdpAndLoopbackRespectPermission"


def require_direct_lan_route(output, peer_host):
    peer = ipaddress.ip_address(peer_host)
    if peer.is_loopback or peer.is_unspecified or not peer.is_private:
        raise RuntimeError("LAN smoke peer must be a private, non-loopback address")
    tokens = output.split()
    if not tokens or tokens[0] != str(peer):
        raise RuntimeError("Device route does not resolve to the requested LAN peer")
    if "via" in tokens:
        raise RuntimeError("LAN smoke peer is routed through a gateway or emulator NAT")
    try:
        device = tokens[tokens.index("dev") + 1]
    except (ValueError, IndexError) as error:
        raise RuntimeError("Device route does not identify a direct interface") from error
    if device == "lo":
        raise RuntimeError("Loopback is not LAN permission evidence")


def require_test_success(output):
    codes = re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$", output, re.MULTILINE)
    if codes != ["1", "0"] or not re.search(r"^OK \(1 test\)\s*$", output, re.MULTILINE):
        raise RuntimeError("Expected exactly one completed smoke test; failure, skip or missing evidence")
    if f"class={TEST_CLASS}" not in output or f"test={TEST_METHOD}" not in output:
        raise RuntimeError("Instrumentation did not execute the required target37 test")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--peer", type=Path, required=True, help="Live fixture JSON with host/tcpPort/udpPort")
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--allow-disposable-device", action="store_true", required=True)
    args = parser.parse_args()
    args.evidence_dir.mkdir(parents=True, exist_ok=True)
    peer = json.loads(args.peer.read_text())

    def adb(*command, timeout=120):
        result = subprocess.run(
            [args.adb, "-s", args.serial, *map(str, command)],
            capture_output=True, text=True, timeout=timeout, check=True,
        )
        return result.stdout.replace("\r", "")

    if adb("shell", "getprop", "ro.build.version.sdk").strip() != "37":
        raise RuntimeError("The selected device is not API 37")
    if adb("shell", "getconf", "PAGE_SIZE").strip() != "16384":
        raise RuntimeError("The selected device does not use 16-KB pages")
    (args.evidence_dir / "getprop.txt").write_text(adb("shell", "getprop"))
    (args.evidence_dir / "page-size.txt").write_text(adb("shell", "getconf", "PAGE_SIZE"))
    (args.evidence_dir / "peer.json").write_text(json.dumps(peer) + "\n")
    peer_route = adb("shell", "ip", "route", "get", peer["host"])
    (args.evidence_dir / "peer-route.txt").write_text(peer_route)
    require_direct_lan_route(peer_route, peer["host"])
    adb("install", "-r", "-t", args.apk)
    adb("install", "-r", "-t", args.test_apk)
    observed = []
    try:
        for phase, granted in [("grant", True), ("deny", False), ("regrant", True)]:
            adb("shell", "am", "force-stop", PACKAGE)
            adb("shell", "pm", "grant" if granted else "revoke", PACKAGE, PERMISSION)
            output = adb(
                "shell", "am", "instrument", "-w", "-r",
                "-e", "class", f"{TEST_CLASS}#{TEST_METHOD}",
                "-e", "lanHost", peer["host"],
                "-e", "lanTcpPort", peer["tcpPort"],
                "-e", "lanUdpPort", peer["udpPort"],
                "-e", "lanGranted", str(granted).lower(),
                f"{PACKAGE}.test/com.poyka.ripdpi.HiltTestRunner",
            )
            (args.evidence_dir / f"{phase}.txt").write_text(output)
            require_test_success(output)
            observed.append(phase)
    finally:
        (args.evidence_dir / "logcat.txt").write_text(adb("logcat", "-d"))
        (args.evidence_dir / "observed-phases.json").write_text(json.dumps(observed) + "\n")
    if observed != ["grant", "deny", "regrant"]:
        raise RuntimeError("Incomplete LAN smoke")


if __name__ == "__main__":
    main()
