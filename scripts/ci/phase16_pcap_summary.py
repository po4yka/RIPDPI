#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from pathlib import Path
from typing import Any


SUMMARY_VERSION = "phase16_pcap_summary_v1"


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def registry_path() -> Path:
    return repo_root() / "scripts" / "ci" / "packet-smoke-scenarios.json"


def load_registry(path: Path | None = None) -> dict[str, dict]:
    actual_path = path or registry_path()
    with actual_path.open("r", encoding="utf-8") as handle:
        return {entry["id"]: entry for entry in json.load(handle)}


def load_capture_packets(scenario_dir: Path) -> list[dict[str, Any]]:
    capture_json = first_existing_path(
        scenario_dir / "capture.tshark.json",
        scenario_dir / "device-capture.tshark.json",
    )
    if capture_json is not None:
        return read_json(capture_json)

    capture_pcap = first_existing_path(
        scenario_dir / "capture.pcap",
        scenario_dir / "device-capture.pcap",
    )
    if capture_pcap is None:
        return []

    with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as handle:
        temp_json = Path(handle.name)
    try:
        subprocess.run(
            ["tshark", "-r", str(capture_pcap), "-T", "json"],
            check=True,
            stdout=temp_json.open("wb"),
            stderr=subprocess.PIPE,
        )
        return read_json(temp_json)
    finally:
        temp_json.unlink(missing_ok=True)


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def first_existing_path(*candidates: Path) -> Path | None:
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return None


def flatten_values(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        result: list[str] = []
        for item in value:
            result.extend(flatten_values(item))
        return result
    if isinstance(value, dict):
        if "show" in value and isinstance(value["show"], str):
            return [value["show"]]
        result: list[str] = []
        for item in value.values():
            result.extend(flatten_values(item))
        return result
    return [str(value)]


def first_field(layers: dict[str, Any], *candidates: str) -> str | None:
    for name in candidates:
        if name in layers:
            values = flatten_values(layers[name])
            if values:
                return values[0]
    return None


def summarize_packets(packets: list[dict[str, Any]]) -> dict[str, Any]:
    ipv4_ttls: set[int] = set()
    tcp_urgent_packets = 0
    quic_versions: set[str] = set()
    udp_source_ports: set[int] = set()
    tcp_destination_ports: set[int] = set()

    for packet in packets:
        layers = packet.get("_source", {}).get("layers", {})
        ttl = first_field(layers, "ip.ttl")
        if ttl and ttl.isdigit():
            ipv4_ttls.add(int(ttl))
        urgent = first_field(layers, "tcp.flags.urg")
        if urgent == "1":
            tcp_urgent_packets += 1
        quic_version = first_field(layers, "quic.version")
        if quic_version:
            quic_versions.add(quic_version)
        udp_src = first_field(layers, "udp.srcport")
        if udp_src and udp_src.isdigit():
            udp_source_ports.add(int(udp_src))
        tcp_dst = first_field(layers, "tcp.dstport")
        if tcp_dst and tcp_dst.isdigit():
            tcp_destination_ports.add(int(tcp_dst))

    return {
        "packetCount": len(packets),
        "ipv4Ttls": sorted(ipv4_ttls),
        "tcpUrgentPackets": tcp_urgent_packets,
        "quicVersions": sorted(quic_versions),
        "udpSourcePorts": sorted(udp_source_ports),
        "tcpDestinationPorts": sorted(tcp_destination_ports),
    }


def summarize_scenario(scenario_dir: Path, registry: dict[str, dict]) -> dict[str, Any]:
    scenario_id = scenario_dir.name
    scenario = registry.get(scenario_id, {})
    expected_artifacts = scenario.get("artifacts", [])
    present_artifacts = sorted(path.name for path in scenario_dir.iterdir() if path.is_file())
    packets = load_capture_packets(scenario_dir)
    summary = summarize_packets(packets)
    return {
        "id": scenario_id,
        "lane": scenario.get("lane"),
        "trafficKind": scenario.get("trafficKind"),
        "expectedArtifacts": expected_artifacts,
        "presentArtifacts": present_artifacts,
        "missingArtifacts": sorted(set(expected_artifacts) - set(present_artifacts)),
        "captureSummary": summary,
    }


def summarize_artifact_root(artifact_root: Path, registry: dict[str, dict]) -> dict[str, Any]:
    if not artifact_root.exists():
        raise FileNotFoundError(f"artifact root does not exist: {artifact_root}")
    scenario_dirs = sorted(path for path in artifact_root.iterdir() if path.is_dir())
    return {
        "version": SUMMARY_VERSION,
        "artifactRoot": str(artifact_root),
        "scenarioCount": len(scenario_dirs),
        "scenarios": [summarize_scenario(path, registry) for path in scenario_dirs],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Summarize Phase 16 packet-smoke capture artifacts")
    parser.add_argument("--artifact-root", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--registry", type=Path, default=registry_path())
    args = parser.parse_args()

    registry = load_registry(args.registry)
    summary = summarize_artifact_root(args.artifact_root, registry)
    payload = json.dumps(summary, indent=2, sort_keys=True)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload + "\n", encoding="utf-8")
    else:
        print(payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
