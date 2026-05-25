#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from pathlib import Path
from typing import Any


SUMMARY_VERSION = "phase16_pcap_summary_v1"
SUPPORT_DIRECTORIES = {"shared", "l7-adversarial"}
L7_VERDICT_REPORT = "l7-adversarial/verdict-report.json"
L7_FAIL_VERDICT = "blocked"
L7_PARTIAL_VERDICTS = {"degraded", "inconclusive"}


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


def load_run_metadata(artifact_root: Path) -> dict[str, Any]:
    manifest = artifact_root / "phase16-run.json"
    if not manifest.exists():
        return {}
    try:
        payload = read_json(manifest)
    except (OSError, json.JSONDecodeError):
        return {}
    return payload if isinstance(payload, dict) else {}


def optional_artifacts(expected_artifacts: list[str], run_metadata: dict[str, Any]) -> list[str]:
    optional: set[str] = set()
    if run_metadata.get("status") == "success":
        optional.add("failure-screenshot.png")
    if run_metadata.get("captureMode") == "indirect":
        optional.add("device-capture.pcap")
    return sorted(optional.intersection(expected_artifacts))


def relative_artifact_path(artifact_root: Path, path: Path) -> str:
    try:
        return path.relative_to(artifact_root).as_posix()
    except ValueError:
        return path.as_posix()


def l7_report_relative_path(run_metadata: dict[str, Any]) -> str:
    configured = run_metadata.get("l7VerdictReport")
    return configured if isinstance(configured, str) and configured else L7_VERDICT_REPORT


def summarize_l7_cells(cells: list[dict[str, Any]]) -> list[dict[str, str]]:
    return [
        {
            "desyncModeId": str(cell.get("desync_mode_id", "")),
            "patternId": str(cell.get("pattern_id", "")),
            "verdict": str(cell.get("verdict", "")),
        }
        for cell in cells
    ]


def summarize_l7_verdict_report(artifact_root: Path, run_metadata: dict[str, Any]) -> dict[str, Any]:
    relative_path = l7_report_relative_path(run_metadata)
    report_path = artifact_root / relative_path
    empty_summary = {
        "present": False,
        "reportPath": relative_path,
        "gateVerdict": "",
        "cellCount": 0,
        "failedCellCount": 0,
        "partialCellCount": 0,
        "totals": {},
        "failedCells": [],
        "partialCells": [],
    }
    if not report_path.exists():
        return empty_summary
    report = read_json(report_path)
    cells = report.get("cells", []) if isinstance(report, dict) else []
    failed_cells = [
        cell
        for cell in cells
        if isinstance(cell, dict) and cell.get("verdict") == L7_FAIL_VERDICT
    ]
    partial_cells = [
        cell
        for cell in cells
        if isinstance(cell, dict) and cell.get("verdict") in L7_PARTIAL_VERDICTS
    ]
    if failed_cells:
        gate_verdict = "fail"
    elif partial_cells or not cells:
        gate_verdict = "partial"
    else:
        gate_verdict = "pass"
    return {
        "present": True,
        "reportPath": relative_artifact_path(artifact_root, report_path),
        "gateVerdict": gate_verdict,
        "reportSchemaVersion": report.get("report_schema_version") if isinstance(report, dict) else None,
        "matrixVersion": report.get("matrix_version") if isinstance(report, dict) else None,
        "mode": report.get("mode", "") if isinstance(report, dict) else "",
        "cellCount": len(cells),
        "failedCellCount": len(failed_cells),
        "partialCellCount": len(partial_cells),
        "totals": report.get("totals", {}) if isinstance(report, dict) else {},
        "failedCells": summarize_l7_cells(failed_cells),
        "partialCells": summarize_l7_cells(partial_cells),
    }


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


def summarize_scenario(scenario_dir: Path, registry: dict[str, dict], run_metadata: dict[str, Any]) -> dict[str, Any]:
    scenario_id = scenario_dir.name
    scenario = registry.get(scenario_id, {})
    expected_artifacts = scenario.get("artifacts", [])
    optional = optional_artifacts(expected_artifacts, run_metadata)
    required_artifacts = sorted(set(expected_artifacts) - set(optional))
    present_artifacts = sorted(path.name for path in scenario_dir.iterdir() if path.is_file())
    packets = load_capture_packets(scenario_dir)
    summary = summarize_packets(packets)
    return {
        "id": scenario_id,
        "lane": scenario.get("lane"),
        "trafficKind": scenario.get("trafficKind"),
        "expectedArtifacts": expected_artifacts,
        "requiredArtifacts": required_artifacts,
        "optionalArtifacts": optional,
        "presentArtifacts": present_artifacts,
        "missingArtifacts": sorted(set(required_artifacts) - set(present_artifacts)),
        "captureSummary": summary,
    }


def summarize_artifact_root(artifact_root: Path, registry: dict[str, dict]) -> dict[str, Any]:
    if not artifact_root.exists():
        raise FileNotFoundError(f"artifact root does not exist: {artifact_root}")
    run_metadata = load_run_metadata(artifact_root)
    l7_summary = summarize_l7_verdict_report(artifact_root, run_metadata)
    scenario_dirs = sorted(
        path for path in artifact_root.iterdir() if path.is_dir() and path.name not in SUPPORT_DIRECTORIES
    )
    return {
        "version": SUMMARY_VERSION,
        "artifactRoot": str(artifact_root),
        "runMetadata": {
            "entryId": run_metadata.get("entryId", ""),
            "status": run_metadata.get("status", ""),
            "failureMessage": run_metadata.get("failureMessage", ""),
            "runnerRequired": run_metadata.get("runnerRequired", "lab"),
            "evidenceTier": run_metadata.get("evidenceTier", "synthetic-lab"),
            "carrierNamespace": run_metadata.get("carrierNamespace", ""),
            "l7VerdictReport": run_metadata.get("l7VerdictReport", ""),
            "realProvider": run_metadata.get("realProvider", {}),
            "prepareHook": run_metadata.get("prepareHook", {}),
        },
        "linkedArtifacts": {
            "l7VerdictReport": l7_summary["reportPath"] if l7_summary["present"] else "",
        },
        "l7Adversarial": l7_summary,
        "scenarioCount": len(scenario_dirs),
        "scenarios": [summarize_scenario(path, registry, run_metadata) for path in scenario_dirs],
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
