"""Dry-run replayer.

For each (desync mode, pattern-or-combination) cell:

- Load the desync mode's packet trace from `fixtures/desync_modes/<mode>.json`.
- For a pattern cell: invoke the pattern's classifier with the configured
  pattern config; map the result to a verdict.
- For a combination cell: invoke each member pattern's classifier; cell
  is blocked when any member matches. `evidence.matched_pattern_ids`
  carries the union of matched ids.
- Write a `<cell>.pcap` evidence artifact containing the trace's packets.

The replayer does not import any kernel-level facilities. It runs on
any host with Python 3.10+.
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


def _pattern_config_lookup(matrix: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {entry["id"]: entry.get("config", {}) for entry in matrix.get("patterns", [])}


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


def replay_combination_cell(
    combination: dict[str, Any],
    pattern_configs: dict[str, dict[str, Any]],
    desync_mode_id: str,
    fixtures_dir: str,
    out_dir: str,
) -> dict[str, Any]:
    """Run every member pattern against the trace; cell verdict is the OR.

    The cell `pattern_id` is `combo:<combination_id>`. `pattern_result`
    is a list of per-member results so triage has the full breakdown.
    `evidence.matched_pattern_ids` is the union of matched member ids.
    """
    combo_id = combination["id"]
    member_ids: list[str] = list(combination.get("patterns", []))
    trace = _load_trace(fixtures_dir, desync_mode_id)

    member_results: list[dict[str, Any]] = []
    matched_member_ids: list[str] = []
    any_force_degraded = bool(trace.get("force_degraded"))
    for member_id in member_ids:
        config = pattern_configs.get(member_id)
        if config is None:
            member_results.append(
                {"pattern_id": member_id, "error": "no config; member id not present in matrix.patterns"}
            )
            continue
        module = _load_pattern_module(member_id)
        result = module.classify(trace, config)
        member_results.append({"pattern_id": member_id, "result": result})
        if result.get("matched"):
            matched_member_ids.append(member_id)

    if matched_member_ids:
        cell_verdict = schema.VERDICT_DEGRADED if any_force_degraded else schema.VERDICT_BLOCKED
    else:
        cell_verdict = schema.VERDICT_BYPASSED

    pcap_name = f"{desync_mode_id}__combo-{combo_id}.pcap"
    pcap_path = os.path.join(out_dir, pcap_name)
    with open(pcap_path, "wb") as fh:
        packet_count = pcap_writer.write_pcap(fh, trace.get("packets", []))

    return {
        "desync_mode_id": desync_mode_id,
        "pattern_id": f"combo:{combo_id}",
        "verdict": cell_verdict,
        "pattern_result": member_results,
        "evidence": {
            "pcap_path": os.path.relpath(pcap_path, out_dir),
            "pcap_packets": packet_count,
            "combination_member_ids": member_ids,
            "matched_pattern_ids": matched_member_ids,
        },
    }


def replay_matrix(matrix: dict[str, Any], fixtures_dir: str, out_dir: str) -> dict[str, Any]:
    os.makedirs(out_dir, exist_ok=True)
    cells = []
    for pattern_entry in matrix.get("patterns", []):
        for desync_mode_id in matrix.get("desync_modes", []):
            cells.append(replay_cell(pattern_entry, desync_mode_id, fixtures_dir, out_dir))
    pattern_configs = _pattern_config_lookup(matrix)
    for combination in matrix.get("combinations", []):
        for desync_mode_id in matrix.get("desync_modes", []):
            cells.append(
                replay_combination_cell(
                    combination, pattern_configs, desync_mode_id, fixtures_dir, out_dir
                )
            )
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
