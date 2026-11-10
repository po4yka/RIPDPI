#!/usr/bin/env python3
"""Pass actual Kotlin relay payload bytes to the native JNI session decoder."""
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]


def main() -> None:
    if sys.platform == "darwin" and os.environ.get("BUILD_GATE_HELD") != "1":
        raise RuntimeError("run through build-gate --lane ripdpi")
    output = ROOT / "core/engine/build/reports/relay-native-wire.json"
    subprocess.run(
        [
            "./gradlew", ":core:engine:testDebugUnitTest",
            "--tests", "com.poyka.ripdpi.core.RelayNativeWireExportTest",
            "--max-workers=4", "-Pripdpi.skipNativeBuild=true",
            "-Pripdpi.requireLibXray=false", "-Pripdpi.exportRelayWire=true",
        ],
        cwd=ROOT,
        check=True,
        timeout=900,
    )
    if not output.is_file():
        raise RuntimeError("the Kotlin test did not produce the wire export")
    result = subprocess.run(
        [
            "cargo", "test", "--locked", "-p", "ripdpi-relay-android", "--lib", "--jobs", "2",
            "kotlin_contract_tests::kotlin_relay_payloads_reach_the_native_session_decoder",
            "--", "--ignored", "--exact", "--nocapture",
        ],
        cwd=ROOT / "native/rust",
        env=dict(os.environ, RIPDPI_KOTLIN_RELAY_WIRE=str(output)),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        timeout=900,
    )
    print(result.stdout, end="", flush=True)
    result.check_returncode()
    if "1 passed; 0 failed; 0 ignored" not in result.stdout:
        raise RuntimeError("the exact native contract test did not execute")


if __name__ == "__main__":
    main()
