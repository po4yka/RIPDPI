#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Iterable


REQUIRED_TRANSPORTS = {"wifi", "cellular"}
REQUIRED_IP_FAMILIES = {"ipv4", "ipv6"}
REQUIRED_MODES = {"proxy", "vpn"}
REQUIRED_ROOTED = {True, False}
BASELINE_NETWORK_CONDITION = "baseline"
REQUIRED_NETWORK_CONDITIONS = {
    BASELINE_NETWORK_CONDITION,
    "pmtud_blackhole",
    "ipv6_extension_blackhole",
    "carrier_nat_reorder",
}
REQUIRED_STRESS_ENTRIES = {
    "wifi_ipv4_nonroot_vpn_pmtud_blackhole",
    "wifi_ipv4_rooted_proxy_ipfrag2_pmtud_blackhole",
    "wifi_ipv6_rooted_proxy_ipfrag2_ipv6_ext_blackhole",
    "cellular_ipv4_nonroot_vpn_carrier_nat_reorder",
}
MATRIX_VERSION = "phase16_lab_matrix_v1"


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def default_fixture_path() -> Path:
    return repo_root() / "contract-fixtures" / "phase16_lab_matrix.json"


def load_fixture(path: Path | None = None) -> dict:
    fixture_path = path or default_fixture_path()
    with fixture_path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def validate_fixture(fixture: dict) -> None:
    version = fixture.get("version")
    if version != MATRIX_VERSION:
        raise ValueError(f"expected matrix version {MATRIX_VERSION}, got {version!r}")

    entries = fixture.get("entries")
    if not isinstance(entries, list) or not entries:
        raise ValueError("matrix fixture must contain a non-empty entries list")

    ids: set[str] = set()
    baseline_combos: set[tuple[str, str, bool, str]] = set()
    network_conditions: set[str] = set()
    for entry in entries:
        validate_entry(entry)
        entry_id = entry["id"]
        if entry_id in ids:
            raise ValueError(f"duplicate matrix entry id: {entry_id}")
        ids.add(entry_id)
        network_condition = entry["networkCondition"]
        network_conditions.add(network_condition)
        if network_condition == BASELINE_NETWORK_CONDITION:
            baseline_combos.add((entry["transport"], entry["ipFamily"], bool(entry["rooted"]), entry["mode"]))

    expected = {
        (transport, ip_family, rooted, mode)
        for transport in REQUIRED_TRANSPORTS
        for ip_family in REQUIRED_IP_FAMILIES
        for rooted in REQUIRED_ROOTED
        for mode in REQUIRED_MODES
    }
    missing = sorted(expected - baseline_combos)
    extra = sorted(baseline_combos - expected)
    if missing or extra:
        raise ValueError(f"baseline matrix coverage mismatch; missing={missing}, extra={extra}")

    missing_conditions = sorted(REQUIRED_NETWORK_CONDITIONS - network_conditions)
    if missing_conditions:
        raise ValueError(f"missing required network conditions: {missing_conditions}")

    missing_stress_entries = sorted(REQUIRED_STRESS_ENTRIES - ids)
    if missing_stress_entries:
        raise ValueError(f"missing required stress entries: {missing_stress_entries}")


def validate_entry(entry: dict) -> None:
    required_keys = {
        "id",
        "transport",
        "ipFamily",
        "rooted",
        "mode",
        "networkCondition",
        "executionKind",
        "scenarioFilter",
        "captureMode",
        "runsOn",
    }
    missing = sorted(required_keys - set(entry))
    if missing:
        raise ValueError(f"matrix entry {entry.get('id', '<unknown>')} missing keys: {missing}")

    if entry["transport"] not in REQUIRED_TRANSPORTS:
        raise ValueError(f"unsupported transport in {entry['id']}: {entry['transport']}")
    if entry["ipFamily"] not in REQUIRED_IP_FAMILIES:
        raise ValueError(f"unsupported ipFamily in {entry['id']}: {entry['ipFamily']}")
    if entry["mode"] not in REQUIRED_MODES:
        raise ValueError(f"unsupported mode in {entry['id']}: {entry['mode']}")
    if entry["networkCondition"] not in REQUIRED_NETWORK_CONDITIONS:
        raise ValueError(f"unsupported networkCondition in {entry['id']}: {entry['networkCondition']}")
    if entry["captureMode"] not in {"raw", "indirect", "auto"}:
        raise ValueError(f"unsupported captureMode in {entry['id']}: {entry['captureMode']}")
    if entry["executionKind"] != "android_packet_smoke":
        raise ValueError(f"unsupported executionKind in {entry['id']}: {entry['executionKind']}")
    if not isinstance(entry["rooted"], bool):
        raise ValueError(f"rooted must be boolean in {entry['id']}")
    if not isinstance(entry["runsOn"], list) or not all(isinstance(item, str) and item for item in entry["runsOn"]):
        raise ValueError(f"runsOn must be a non-empty string list in {entry['id']}")
    if "self-hosted" not in entry["runsOn"]:
        raise ValueError(f"runsOn must include self-hosted for {entry['id']}")


def filtered_entries(
    fixture: dict,
    entry_filter: str | None = None,
) -> list[dict]:
    entries = fixture["entries"]
    if not entry_filter:
        return entries
    filters = {item.strip() for item in entry_filter.split(",") if item.strip()}
    filtered = [entry for entry in entries if entry["id"] in filters]
    if not filtered:
        raise ValueError(f"no matrix entries matched filter: {entry_filter}")
    return filtered


def emit_github_matrix(entries: Iterable[dict]) -> dict:
    include = []
    for entry in entries:
        include.append(
            {
                "id": entry["id"],
                "transport": entry["transport"],
                "ipFamily": entry["ipFamily"],
                "rooted": entry["rooted"],
                "mode": entry["mode"],
                "networkCondition": entry["networkCondition"],
                "executionKind": entry["executionKind"],
                "scenarioFilter": entry["scenarioFilter"],
                "captureMode": entry["captureMode"],
                "runsOnJson": json.dumps(entry["runsOn"]),
                "artifactName": f"phase16-{entry['id']}",
            }
        )
    return {"include": include}


def main() -> int:
    parser = argparse.ArgumentParser(description="Phase 16 lab matrix helper")
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate_parser = subparsers.add_parser("validate", help="Validate the matrix fixture")
    validate_parser.add_argument("--fixture", type=Path, default=default_fixture_path())

    emit_parser = subparsers.add_parser("emit-github-matrix", help="Emit a GitHub Actions matrix JSON payload")
    emit_parser.add_argument("--fixture", type=Path, default=default_fixture_path())
    emit_parser.add_argument("--filter", default="", help="Comma-separated entry ids to include")

    list_parser = subparsers.add_parser("list", help="List matrix entry ids")
    list_parser.add_argument("--fixture", type=Path, default=default_fixture_path())

    args = parser.parse_args()
    fixture = load_fixture(args.fixture)
    validate_fixture(fixture)

    if args.command == "validate":
        return 0
    if args.command == "emit-github-matrix":
        payload = emit_github_matrix(filtered_entries(fixture, args.filter))
        print(json.dumps(payload, separators=(",", ":")))
        return 0
    if args.command == "list":
        for entry in fixture["entries"]:
            print(entry["id"])
        return 0
    raise AssertionError(f"unhandled command: {args.command}")


if __name__ == "__main__":
    raise SystemExit(main())
