"""Recheck a saved report and all public evidence hashes before sign-off."""

import hashlib
import json
from pathlib import Path

from contract import (
    load_json,
    require,
    validate_manifest,
    validate_result,
    unique_strings,
)


def verify(directory: Path) -> dict:
    report = load_json(directory / "report.json")
    require(report.get("status") == "passed", "report did not pass")
    require(report.get("source_unchanged") is True, "source changed during execution")
    manifest = validate_manifest(load_json(directory / "manifest.json"))
    require(
        hashlib.sha256(json.dumps(manifest, sort_keys=True).encode()).hexdigest()
        == report.get("manifest_sha256"),
        "manifest hash mismatch",
    )
    selected = unique_strings(report.get("selected_scenarios"), "selected scenarios")
    rows = report.get("results")
    require(isinstance(rows, list), "result rows missing")
    identities = unique_strings(
        [row.get("scenario_id") for row in rows], "result scenarios"
    )
    require(identities == selected, "scenario results differ from selection")
    scenarios = {row["id"]: row for row in manifest["scenarios"]}
    for row in rows:
        identity = row["scenario_id"]
        require(
            identity in scenarios and row.get("status") == "passed",
            "scenario did not pass",
        )
        path = directory / identity / "result.json"
        require(
            not path.is_symlink() and not path.parent.is_symlink(),
            "symlink result rejected",
        )
        require(
            hashlib.sha256(path.read_bytes()).hexdigest() == row.get("result_sha256"),
            "result hash mismatch",
        )
        verified = validate_result(
            load_json(path),
            scenarios[identity],
            report["run_id"],
            report["source_sha"],
            path.parent,
        )
        require(
            verified["artifact_hashes"] == row.get("artifact_hashes"),
            "evidence hash mismatch",
        )
    return report
