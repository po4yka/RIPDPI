#!/usr/bin/env python3
"""Build the shared manifest from the executable adapter catalogs."""

import argparse
import json
from pathlib import Path
import runpy
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def generate():
    peers = runpy.run_path(str(HERE / "peers/catalog.py"))
    android = runpy.run_path(str(ROOT / "scripts/ci/run-android-local-acceptance.py"))
    sys.path.insert(0, str(HERE / "vm"))
    try:
        vm = runpy.run_path(str(HERE / "vm/run.py"))
    finally:
        sys.path.pop(0)
    rows = []
    for identity, case in peers["SCENARIOS"].items():
        rows.append(
            {
                "id": identity,
                "adapter": "peers",
                "protocol": case["protocol"],
                "tier": case["tier"],
                "platforms": ["darwin", "linux"],
                "timeout_seconds": 1800,
                "checks": case["checks"],
            }
        )
    for identity, methods in android["SCENARIOS"].items():
        rows.append(
            {
                "id": identity,
                "adapter": "android",
                "protocol": "vless-reality-xhttp"
                if identity == "android-xray"
                else "direct-tun-dns",
                "tier": "android-tun",
                "platforms": ["darwin", "linux"],
                "timeout_seconds": 7200,
                "checks": [method.split("#")[1] for method in methods],
            }
        )
    for identity in vm["SCENARIOS"]:
        rows.append(
            {
                "id": identity,
                "adapter": "vm",
                "protocol": "tcp-udp-ipv4-ipv6",
                "tier": "packet-fidelity"
                if identity.startswith("packet-")
                else "routed-fault",
                "platforms": ["linux"],
                "timeout_seconds": 3600 if identity == "packet-engine" else 300,
                "checks": vm["REQUIRED_CHECKS"][identity],
            }
        )
    profiles = {
        "native": [r["id"] for r in rows if r["adapter"] == "peers"],
        "android": [r["id"] for r in rows if r["adapter"] == "android"],
        "routed": [
            r["id"] for r in rows if r["adapter"] == "vm" and r["id"] != "packet-engine"
        ],
        "packet": ["packet-engine"],
        "core": [
            "android-xray",
            "android-network",
            "independent-hysteria2-tcp-udp",
            "independent-hysteria2-auth-rejection",
            "independent-hysteria2-tls-rejection",
            "independent-awg-tcp-udp",
            "routed-baseline-drop-recovery",
        ],
        "full": [r["id"] for r in rows],
    }
    boundaries = [
        {"id": key.replace("_", "-"), "reason": value}
        for key, value in peers["EXTERNAL_BOUNDARIES"].items()
    ]
    boundaries.extend(
        [
            {
                "id": "physical-android",
                "reason": "Emulator acceptance does not replace OEM power management or physical Wi-Fi/cellular handover.",
            },
            {
                "id": "carrier-dpi",
                "reason": "Local fault classifiers do not reproduce every ISP packet inspection system.",
            },
        ]
    )
    return {
        "schema_version": 1,
        "profiles": profiles,
        "external_boundaries": boundaries,
        "scenarios": rows,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    rendered = json.dumps(generate(), indent=2) + "\n"
    target = HERE / "manifest.json"
    if args.check:
        if not target.exists() or target.read_text() != rendered:
            print("Acceptance catalog drift: run generate_manifest.py", file=sys.stderr)
            return 1
    else:
        target.write_text(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
