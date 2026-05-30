from __future__ import annotations

from pathlib import Path
from typing import Any

from .cluster import run_cluster
from .common import ROOT, load_json, now_iso_utc
from .publish import (
    build_device_fingerprint_catalog,
    build_winner_mapping_catalog,
    empty_device_catalog,
)
from .simulate import simulate_corpus

# Default fixture of known field failures shipped alongside this module.  These
# are synthetic stand-ins until a real field-failure archive is curated; see the
# top-level ``note`` in the JSON for the honest caveat.
DEFAULT_CALIBRATION_FIXTURE = ROOT / "scripts/analytics/calibration-field-failures.json"

# Sim-to-field agreement below this fraction is treated as a calibration failure
# so CI can gate before any generated pack ships.
CALIBRATION_THRESHOLD = 0.8


def load_calibration_fixture(path: Path) -> dict[str, Any]:
    """Load the known-field-failures fixture, validating its minimal shape."""
    fixture = load_json(path)
    entries = fixture.get("entries")
    if not isinstance(entries, list) or not entries:
        raise ValueError(f"Calibration fixture {path} has no entries.")
    for entry in entries:
        if not entry.get("scenarioId") or not entry.get("expectedWinnerFamily"):
            raise ValueError(
                "Each calibration entry needs scenarioId and expectedWinnerFamily."
            )
    return fixture


def simulated_recommended_family(scenario_id: str, seed: int, count: int) -> str | None:
    """Run the simulator for one scenario through the pipeline's winner-mapping.

    Reuses the SAME selection logic the publish pipeline uses
    (``build_winner_mapping_catalog`` over a clustered device catalog), so the
    family reported here is exactly the one a shipped pack would carry.
    """
    payload = simulate_corpus([scenario_id], seed=seed, count=count)
    clustered = run_cluster(payload)
    device_catalog = build_device_fingerprint_catalog(
        clustered_payload=clustered,
        corpus_name="calibration",
        previous_catalog=empty_device_catalog(review_status="reviewed"),
    )
    winner_catalog = build_winner_mapping_catalog(
        extracted_payload=payload,
        device_catalog=device_catalog,
        corpus_name="calibration",
    )
    families = {
        mapping["recommendedWinnerFamily"]
        for mapping in winner_catalog.get("mappings", [])
        if mapping.get("recommendedWinnerFamily")
    }
    if not families:
        return None
    # Deterministic pick: the alphabetically-first recommended family.  For a
    # single-scenario corpus the simulator yields exactly one family, so this is
    # unambiguous; sorting only guards against accidental multiplicity.
    return sorted(families)[0]


def run_calibration(fixture: dict[str, Any], seed: int, count: int) -> dict[str, Any]:
    """Score sim-to-field agreement across every fixture entry.

    For each entry, run the simulator + winner-mapping selection to get the
    simulated recommended family and compare it to the field-observed expected
    family.  ``agreementScore`` is the fraction of entries that agree.
    """
    per_entry: list[dict[str, Any]] = []
    matched = 0
    for entry in fixture["entries"]:
        scenario_id = entry["scenarioId"]
        expected = entry["expectedWinnerFamily"]
        simulated = simulated_recommended_family(scenario_id, seed=seed, count=count)
        agree = simulated == expected
        if agree:
            matched += 1
        per_entry.append(
            {
                "scenarioId": scenario_id,
                "expected": expected,
                "simulated": simulated,
                "agree": agree,
            }
        )
    entry_count = len(per_entry)
    agreement_score = round(matched / entry_count, 2) if entry_count else 0.0
    return {
        "generatedAt": now_iso_utc(),
        "seed": seed,
        "entryCount": entry_count,
        "agreementScore": agreement_score,
        "matched": matched,
        "perEntry": per_entry,
    }
