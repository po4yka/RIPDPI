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


def decode_tcp_payload(value: str | None) -> bytes | None:
    if not value:
        return None
    compact = value.replace(":", "").replace(" ", "")
    try:
        return bytes.fromhex(compact)
    except ValueError:
        return None


def summarize_tcp_split_at_sni_host_delta(
    packets: list[dict[str, Any]],
    fixture_domain: str,
    tls_port: int,
    marker_delta: int,
) -> dict[str, Any]:
    streams: dict[str, list[tuple[int, bytes]]] = {}
    seen_segments: set[tuple[str, int, bytes]] = set()
    for packet in packets:
        layers = packet.get("_source", {}).get("layers", {})
        if first_field(layers, "tcp.dstport") != str(tls_port):
            continue
        stream = first_field(layers, "tcp.stream")
        sequence = first_field(layers, "tcp.seq_raw", "tcp.seq")
        payload = decode_tcp_payload(first_field(layers, "tcp.payload"))
        if not stream or sequence is None or not payload:
            continue
        try:
            sequence_number = int(sequence, 0)
        except ValueError:
            continue
        segment_key = (stream, sequence_number, payload)
        if segment_key in seen_segments:
            continue
        seen_segments.add(segment_key)
        streams.setdefault(stream, []).append((sequence_number, payload))

    expected_host = fixture_domain.encode("ascii", errors="strict")
    expected_boundary_delta = marker_delta
    saw_ambiguous_stream = False
    failed_match: dict[str, Any] | None = None
    for stream, segments in streams.items():
        ordered_segments = sorted(segments, key=lambda segment: (segment[0], len(segment[1])))
        base_sequence = ordered_segments[0][0]
        end_sequence = max(sequence + len(payload) for sequence, payload in ordered_segments)
        reconstructed: list[int | None] = [None] * (end_sequence - base_sequence)
        conflicting_overlap = False
        for sequence, payload in ordered_segments:
            offset = sequence - base_sequence
            for index, byte in enumerate(payload):
                position = offset + index
                existing = reconstructed[position]
                if existing is not None and existing != byte:
                    conflicting_overlap = True
                    break
                reconstructed[position] = byte
            if conflicting_overlap:
                break
        if conflicting_overlap or any(byte is None for byte in reconstructed):
            saw_ambiguous_stream = True
            continue
        reassembled = bytes(byte for byte in reconstructed if byte is not None)
        segment_end_sequences = sorted({sequence + len(payload) for sequence, payload in ordered_segments})
        segment_end_offsets = [sequence - base_sequence for sequence in segment_end_sequences]
        host_offset = reassembled.find(expected_host)
        if host_offset < 0:
            continue
        expected_boundary = host_offset + expected_boundary_delta
        expected_boundary_sequence = base_sequence + expected_boundary
        passed = expected_boundary_sequence in segment_end_sequences and expected_boundary_sequence < end_sequence
        match = {
            "kind": "tcp_split_at_sni_host_delta",
            "status": "passed" if passed else "failed",
            "markerDelta": marker_delta,
            "matchedStream": stream,
            "hostOffset": host_offset,
            "expectedBoundaryOffset": expected_boundary,
            "segmentEndOffsets": segment_end_offsets,
        }
        if passed:
            return match
        failed_match = match
    if failed_match is not None:
        return failed_match
    return {
        "kind": "tcp_split_at_sni_host_delta",
        "status": "unavailable" if saw_ambiguous_stream or not streams else "failed",
        "markerDelta": marker_delta,
        "matchedStream": "",
        "hostOffset": None,
        "expectedBoundaryOffset": None,
        "segmentEndOffsets": [],
    }


def summarize_packet_assertion(
    scenario: dict[str, Any],
    scenario_dir: Path,
    packets: list[dict[str, Any]],
) -> dict[str, Any] | None:
    assertion = scenario.get("packetAssertion")
    if not isinstance(assertion, dict):
        return None
    kind = assertion.get("kind")
    if kind != "tcp_split_at_sni_host_delta":
        return {"kind": str(kind or "unknown"), "status": "unsupported"}
    manifest_path = scenario_dir / "fixture-manifest.json"
    if not manifest_path.exists():
        return {"kind": kind, "status": "unavailable"}
    manifest = read_json(manifest_path)
    try:
        fixture_domain = str(manifest["fixtureDomain"])
        tls_port = int(manifest["tlsEchoPort"])
        marker_delta = int(assertion["markerDelta"])
    except (KeyError, TypeError, ValueError):
        return {"kind": kind, "status": "unavailable"}
    return summarize_tcp_split_at_sni_host_delta(packets, fixture_domain, tls_port, marker_delta)


def summarize_scenario(scenario_dir: Path, registry: dict[str, dict], run_metadata: dict[str, Any]) -> dict[str, Any]:
    scenario_id = scenario_dir.name
    scenario = registry.get(scenario_id, {})
    expected_artifacts = scenario.get("artifacts", [])
    optional = optional_artifacts(expected_artifacts, run_metadata)
    required_artifacts = sorted(set(expected_artifacts) - set(optional))
    present_artifacts = sorted(path.name for path in scenario_dir.iterdir() if path.is_file())
    packets = load_capture_packets(scenario_dir)
    summary = summarize_packets(packets)
    packet_assertion = summarize_packet_assertion(scenario, scenario_dir, packets)
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
        "packetAssertion": packet_assertion,
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
    parser.add_argument("--require-packet-assertions", action="store_true")
    args = parser.parse_args()

    registry = load_registry(args.registry)
    summary = summarize_artifact_root(args.artifact_root, registry)
    payload = json.dumps(summary, indent=2, sort_keys=True)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload + "\n", encoding="utf-8")
    else:
        print(payload)
    if args.require_packet_assertions:
        assertions = [scenario["packetAssertion"] for scenario in summary["scenarios"] if scenario["packetAssertion"]]
        if any(assertion.get("status") != "passed" for assertion in assertions):
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
