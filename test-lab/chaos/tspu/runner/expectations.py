"""Validate the full fixture replay against the independent verdict oracle.

A passing receipt proves classifier behavior for static fixtures. It never
establishes acceptance of the engine or a provider network.
"""
from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import struct
from typing import Any

from .schema import ALL_VERDICTS, REPORT_SCHEMA_VERSION


def load_expected_verdicts(tspu_dir: Path) -> dict[tuple[str, str], str]:
    oracle = json.loads((tspu_dir / "expected-verdicts.json").read_text(encoding="utf-8"))
    matrix = json.loads((tspu_dir / "matrix.json").read_text(encoding="utf-8"))
    if (oracle.get("expectation_schema_version") != 1
            or oracle.get("report_schema_version") != REPORT_SCHEMA_VERSION
            or oracle.get("matrix_version") != matrix.get("matrix_version")):
        raise ValueError("classifier oracle version mismatch")
    expected = {}
    for cell in oracle["cells"]:
        key = (cell["pattern_id"], cell["desync_mode_id"])
        if key in expected or cell["verdict"] not in ALL_VERDICTS:
            raise ValueError("duplicate or invalid classifier oracle cell")
        expected[key] = cell["verdict"]
    pattern_ids = [entry["id"] for entry in matrix["patterns"]]
    pattern_ids += ["combo:" + entry["id"] for entry in matrix["combinations"]]
    keys = {(pattern, mode) for pattern in pattern_ids for mode in matrix["desync_modes"]}
    if keys != set(expected):
        raise ValueError("classifier oracle does not cover the full matrix")
    return expected


def pcap_packet_count(path: Path) -> int:
    """Require a complete classic PCAP with at least one non-empty record."""
    data = path.read_bytes()
    if len(data) < 24:
        raise ValueError("PCAP header is missing")
    endian = {b"\xa1\xb2\xc3\xd4": ">", b"\xd4\xc3\xb2\xa1": "<"}.get(data[:4])
    if endian is None:
        raise ValueError("unsupported PCAP magic")
    major, minor, _, _, snaplen, linktype = struct.unpack(endian + "HHIIII", data[4:24])
    if (major, minor) != (2, 4) or snaplen == 0 or linktype != 1:
        raise ValueError("invalid fixture PCAP header")
    offset, count = 24, 0
    while offset < len(data):
        if offset + 16 > len(data):
            raise ValueError("truncated PCAP record header")
        _, micros, captured, original = struct.unpack(endian + "IIII", data[offset:offset + 16])
        offset += 16
        if micros >= 1_000_000 or not captured or captured > snaplen or captured > original:
            raise ValueError("invalid PCAP record length or timestamp")
        if offset + captured > len(data):
            raise ValueError("truncated PCAP packet")
        offset += captured
        count += 1
    if not count:
        raise ValueError("PCAP has no packets")
    return count


def validate_report(report_path: Path, repo_root: Path) -> dict[str, Any]:
    receipt: dict[str, Any] = {
        "purpose": "classifier-self-test", "gateVerdict": "fail", "releaseAcceptance": False,
        "errors": [], "coverage": {}, "mismatchedCells": [],
    }
    errors = receipt["errors"]
    try:
        tspu_dir = repo_root / "test-lab/chaos/tspu"
        expected = load_expected_verdicts(tspu_dir)
        matrix = json.loads((tspu_dir / "matrix.json").read_text(encoding="utf-8"))
        report = json.loads(report_path.read_text(encoding="utf-8"))
        if not isinstance(report, dict):
            raise ValueError("report must be an object")
        for field, value in (("report_schema_version", REPORT_SCHEMA_VERSION),
                             ("matrix_version", matrix["matrix_version"]), ("mode", "dry-run")):
            if type(report.get(field)) is not type(value) or report.get(field) != value:
                errors.append(f"invalid {field}: expected {value!r}")
        cells = report.get("cells")
        if not isinstance(cells, list):
            raise ValueError("cells must be a list")
        seen, pcap_paths, totals = set(), set(), Counter()
        for index, cell in enumerate(cells):
            if not isinstance(cell, dict):
                errors.append(f"cell {index} must be an object")
                continue
            key = (cell.get("pattern_id"), cell.get("desync_mode_id"))
            if not all(isinstance(value, str) for value in key):
                errors.append(f"cell {index} has invalid identifiers")
                continue
            if key in seen:
                errors.append(f"duplicate cell {key}")
            seen.add(key)
            verdict = cell.get("verdict")
            if not isinstance(verdict, str) or verdict not in ALL_VERDICTS:
                errors.append(f"unknown verdict in {key}")
            else:
                totals[verdict] += 1
            if key not in expected or verdict != expected[key]:
                errors.append(f"unexpected verdict or cell {key}: {verdict!r}, expected {expected.get(key)!r}")
                receipt["mismatchedCells"].append({"patternId": key[0], "desyncModeId": key[1],
                                                  "verdict": verdict, "expectedVerdict": expected.get(key)})
            try:
                evidence = cell["evidence"]
                relative = evidence["pcap_path"]
                if not isinstance(relative, str) or not relative:
                    raise ValueError("PCAP path must be a non-empty relative path")
                path = Path(relative)
                if path.is_absolute() or ".." in path.parts:
                    raise ValueError("PCAP path leaves the report directory")
                resolved = (report_path.parent / path).resolve()
                resolved.relative_to(report_path.parent.resolve())
                if resolved in pcap_paths:
                    raise ValueError("PCAP path is shared by multiple cells")
                pcap_paths.add(resolved)
                count = pcap_packet_count(resolved)
                if type(evidence.get("pcap_packets")) is not int or evidence["pcap_packets"] != count:
                    raise ValueError("PCAP packet count does not match evidence")
            except (OSError, ValueError, KeyError, TypeError, struct.error) as error:
                errors.append(f"invalid PCAP evidence for {key}: {error}")
        missing = expected.keys() - seen
        if missing:
            errors.append(f"missing {len(missing)} expected cells")
        expected_totals = {verdict: totals[verdict] for verdict in ALL_VERDICTS}
        reported_totals = report.get("totals")
        if (not isinstance(reported_totals, dict) or reported_totals != expected_totals
                or any(type(value) is not int or value < 0 for value in reported_totals.values())):
            errors.append("totals do not match the cell verdicts")
        receipt["coverage"] = {"expectedCellCount": len(expected), "cellCount": len(cells),
                               "uniqueCellCount": len(seen), "missingCellCount": len(missing),
                               "totals": expected_totals}
    except (OSError, ValueError, KeyError, TypeError) as error:
        errors.append(str(error))
    if not errors:
        receipt["gateVerdict"] = "pass"
    return receipt


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--receipt", type=Path)
    args = parser.parse_args()
    receipt = validate_report(args.report, args.repo_root)
    rendered = json.dumps(receipt, indent=2, sort_keys=True) + "\n"
    if args.receipt:
        args.receipt.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0 if receipt["gateVerdict"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
