#!/usr/bin/env python3
"""Observe private adb TCP tables inside nonce-bound, live denial windows."""
from __future__ import annotations

import argparse
import ipaddress
import json
import re
import subprocess
import time
from pathlib import Path

from check_android_so_bind_physical_evidence import summarize_socket_sample

WINDOW = "files/so-bind-socket-window.json"
ACK = "files/so-bind-socket-ack.txt"


def observe(adb: str, serial: str, run_id: str, stop: Path, timeout: int) -> list[dict[str, object]]:
    prefix = [adb, "-s", serial, "shell"]

    def shell(*args: str, optional: bool = False, data: str | None = None) -> str:
        result = subprocess.run(prefix + list(args), input=data, capture_output=True, text=True, timeout=10, check=False)
        if result.returncode and not optional:
            raise ValueError("ADB socket observation unavailable")
        return result.stdout if result.returncode == 0 else ""

    def sample(window: dict[str, object]) -> dict[str, int]:
        return summarize_socket_sample(shell("cat", "/proc/net/tcp"), shell("cat", "/proc/net/tcp6"),
                                       uid=window["uid"], host=window["host"],
                                       control_port=window["controlPort"], denied_port=window["deniedPort"])

    def acknowledge(family: str, phase: str) -> None:
        # The only shell-redirection operand is this constant app-private path.
        shell("run-as", "com.poyka.ripdpi", "sh", "-c", "'cat > files/so-bind-socket-ack.txt'",
              data=f"{run_id}:{family}:{phase}")

    deadline = time.monotonic() + timeout
    results: list[dict[str, object]] = []
    current: dict[str, object] | None = None
    samples = 0
    minimum_positive = 0
    last_phase = ""
    while time.monotonic() < deadline:
        raw = shell("run-as", "com.poyka.ripdpi", "cat", WINDOW, optional=True)
        if not raw:
            if stop.exists():
                if current is not None:
                    raise ValueError("denial window ended without synchronized completion")
                return results
            time.sleep(0.1)
            continue
        window = json.loads(raw)
        expected = {"runId", "family", "phase", "uid", "host", "controlPort", "deniedPort"}
        if not isinstance(window, dict) or set(window) != expected or window["runId"] != run_id:
            raise ValueError("socket observation nonce or fields mismatch")
        family = window["family"]
        if family not in ("ipv4", "ipv6") or window["phase"] not in ("ready", "active", "done"):
            raise ValueError("socket observation family or phase malformed")
        if type(window["uid"]) is not int or window["uid"] < 10000:
            raise ValueError("socket observation UID malformed")
        if ipaddress.ip_address(window["host"]).version != (6 if family == "ipv6" else 4):
            raise ValueError("socket observation family mismatch")
        if any(type(window[key]) is not int or not 0 < window[key] <= 65535 for key in ("controlPort", "deniedPort")):
            raise ValueError("socket observation endpoint malformed")
        if results and results[-1]["family"] == family:
            if stop.exists():
                return results
            time.sleep(0.1)
            continue
        identity = {key: value for key, value in window.items() if key != "phase"}
        if current is None:
            if window["phase"] != "ready" or family != ("ipv4" if not results else "ipv6"):
                raise ValueError("late or out-of-order socket capture")
            sample(window)
            current = identity
            samples = 0
            minimum_positive = 0
            last_phase = "ready"
            acknowledge(family, "start")
        elif current != identity:
            raise ValueError("socket observation identity changed mid-window")
        elif window["phase"] == "active":
            value = sample(window)
            samples += 1
            positive = value["positiveControlRows"]
            minimum_positive = min(minimum_positive, positive) if minimum_positive else positive
            last_phase = "active"
        elif window["phase"] == "done":
            if last_phase != "active" or samples < 3:
                raise ValueError("insufficient live denial socket samples")
            sample(window)
            results.append({"family": family, "liveSamples": samples,
                            "minimumPositiveControlRows": minimum_positive, "deniedRemoteRows": 0,
                            "synchronized": True})
            acknowledge(family, "done")
            current = None
        elif last_phase != "ready":
            raise ValueError("socket observation phase regressed")
        if stop.exists() and current is not None:
            raise ValueError("socket capture interrupted before handshake completion")
        time.sleep(0.1)
    raise ValueError("socket observation deadline exceeded")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--stop", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{32}", args.run_id):
        parser.error("invalid nonce")
    try:
        result = observe(args.adb, args.serial, args.run_id, args.stop, args.timeout)
        args.output.write_text(json.dumps(result), encoding="utf-8")
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        # Never print raw process output, socket tuples, UIDs, or addresses.
        if isinstance(error, ValueError) and "leaked app-owned" in str(error):
            print("SO_BIND socket observation detected a leaked remote connection")
            return 1
        print("SO_BIND socket observation unavailable, malformed, or late")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
