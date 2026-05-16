"""Dry-run replayer.

For each (desync mode, pattern) cell:

- Load the desync mode's packet trace from `fixtures/desync_modes/<mode>.json`.
- Invoke the pattern's classifier with the configured pattern config.
- Map the classifier output to a verdict via `runner.classifier.verdict_for`.
- Write a `<cell>.pcap` evidence artifact containing the trace's packets.

The replayer does not import any kernel-level facilities. It can run on
any host that has Python 3.10+.
"""

from __future__ import annotations

import importlib
import json
import os
from typing import Any

from . import classifier, pcap_writer, schema


def _load_trace(fixtures_dir: str, desync_mode_id: str) -> dict[str, Any]:
    path = os.path.join(fixtures_dir, "desync_modes", f"{desync_mode_id}.json")
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def _load_pattern_module(pattern_id: str):
    module_name = pattern_id.replace("-", "_")
    return importlib.import_module(f"patterns.{module_name}")


def replay_cell(
    pattern_entry: dict[str, Any],
    desync_mode_id: str,
    fixtures_dir: str,
    out_dir: str,
) -> dict[str, Any]:
    pattern_id = pattern_entry["id"]
    pattern_module = _load_pattern_module(pattern_id)
    trace = _load_trace(fixtures_dir, desync_mode_id)
    pattern_result = pattern_module.classify(trace, pattern_entry.get("config", {}))
    verdict = classifier.verdict_for(pattern_result, trace)
    pcap_name = f"{desync_mode_id}__{pattern_id}.pcap"
    pcap_path = os.path.join(out_dir, pcap_name)
    with open(pcap_path, "wb") as fh:
        packet_count = pcap_writer.write_pcap(fh, trace.get("packets", []))
    return {
        "desync_mode_id": desync_mode_id,
        "pattern_id": pattern_id,
        "verdict": verdict,
        "pattern_result": pattern_result,
        "evidence": {
            "pcap_path": os.path.relpath(pcap_path, out_dir),
            "pcap_packets": packet_count,
        },
    }


def replay_matrix(matrix: dict[str, Any], fixtures_dir: str, out_dir: str) -> dict[str, Any]:
    os.makedirs(out_dir, exist_ok=True)
    cells = []
    for pattern_entry in matrix.get("patterns", []):
        for desync_mode_id in matrix.get("desync_modes", []):
            cells.append(replay_cell(pattern_entry, desync_mode_id, fixtures_dir, out_dir))
    return {
        "report_schema_version": schema.REPORT_SCHEMA_VERSION,
        "matrix_version": matrix.get("matrix_version"),
        "mode": "dry-run",
        "cells": cells,
        "totals": _totals(cells),
    }


def _totals(cells: list[dict[str, Any]]) -> dict[str, int]:
    counts = {v: 0 for v in schema.ALL_VERDICTS}
    for cell in cells:
        counts[cell["verdict"]] = counts.get(cell["verdict"], 0) + 1
    return counts
