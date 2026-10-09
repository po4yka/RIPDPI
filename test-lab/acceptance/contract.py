"""Closed acceptance catalog and evidence validation, with no external dependencies."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re

ADAPTERS = {
    "android": "scripts/ci/run-android-local-acceptance.py",
    "peers": "test-lab/acceptance/peers/run.py",
    "vm": "test-lab/acceptance/vm/run.py",
}
TIERS = {
    "native-contract",
    "independent-peer",
    "android-tun",
    "routed-fault",
    "packet-fidelity",
}
IDENTIFIER = re.compile(r"[a-z][a-z0-9-]{0,95}\Z")


class ContractError(ValueError):
    """Evidence cannot support the requested acceptance claim."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractError(message)


def unique_strings(value: object, label: str, *, identifiers: bool = True) -> list[str]:
    require(isinstance(value, list) and bool(value), f"{label}: nonempty list required")
    require(
        all(isinstance(item, str) and item for item in value),
        f"{label}: strings required",
    )
    require(len(value) == len(set(value)), f"{label}: duplicate entries")
    if identifiers:
        require(
            all(IDENTIFIER.fullmatch(item) for item in value),
            f"{label}: invalid identifier",
        )
    return value


def load_json(path: Path) -> dict:
    def no_duplicates(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "duplicate JSON key")
            result[key] = value
        return result

    try:
        data = json.loads(path.read_text(), object_pairs_hook=no_duplicates)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ContractError(f"Cannot read JSON: {path.name}") from error
    require(isinstance(data, dict), "JSON object required")
    return data


def validate_manifest(data: dict) -> dict:
    require(
        type(data.get("schema_version")) is int and data["schema_version"] == 1,
        "unsupported manifest schema",
    )
    scenarios = data.get("scenarios")
    require(isinstance(scenarios, list) and bool(scenarios), "scenarios required")
    ids = []
    for item in scenarios:
        require(isinstance(item, dict), "scenario object required")
        identity = item.get("id")
        require(
            isinstance(identity, str) and IDENTIFIER.fullmatch(identity),
            "invalid scenario id",
        )
        require(item.get("adapter") in ADAPTERS, f"{identity}: unknown adapter")
        require(item.get("tier") in TIERS, f"{identity}: unknown evidence tier")
        require(
            isinstance(item.get("protocol"), str) and bool(item["protocol"]),
            f"{identity}: protocol required",
        )
        platforms = unique_strings(item.get("platforms"), "platforms")
        require(set(platforms) <= {"linux", "darwin"}, "unsupported platform")
        timeout = item.get("timeout_seconds")
        require(
            type(timeout) is int and 1 <= timeout <= 14400, "invalid scenario timeout"
        )
        unique_strings(item.get("checks"), "checks", identifiers=False)
        ids.append(identity)
    unique_strings(ids, "scenarios")
    profiles = data.get("profiles")
    require(isinstance(profiles, dict) and bool(profiles), "profiles required")
    for name, selection in profiles.items():
        require(bool(IDENTIFIER.fullmatch(name)), "invalid profile id")
        require(
            set(unique_strings(selection, name)) <= set(ids),
            f"{name}: unknown scenario",
        )
    boundaries = data.get("external_boundaries")
    require(isinstance(boundaries, list), "external boundaries required")
    boundary_ids = []
    for boundary in boundaries:
        require(
            isinstance(boundary, dict)
            and isinstance(boundary.get("reason"), str)
            and bool(boundary["reason"]),
            "external boundary reason required",
        )
        boundary_ids.append(boundary.get("id"))
    if boundary_ids:
        unique_strings(boundary_ids, "external boundaries")
    return data


def artifact_hashes(root: Path, names: object) -> dict[str, str]:
    paths = unique_strings(names, "artifacts", identifiers=False)
    hashes = {}
    for name in paths:
        relative = Path(name)
        require(
            not relative.is_absolute() and ".." not in relative.parts,
            "artifact escapes result directory",
        )
        require(
            relative.suffix.lower() not in {".pcap", ".pcapng", ".key", ".pem"},
            "private artifact cannot be published",
        )
        target = root / relative
        require(
            not any(
                (root / Path(*relative.parts[:i])).is_symlink()
                for i in range(1, len(relative.parts) + 1)
            ),
            "symlink artifact rejected",
        )
        require(
            target.is_file() and target.resolve().is_relative_to(root.resolve()),
            "missing artifact",
        )
        digest = hashlib.sha256()
        with target.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
        hashes[name] = digest.hexdigest()
    return hashes


def validate_result(
    data: dict, scenario: dict, run_id: str, source_sha: str, root: Path
) -> dict:
    require(
        type(data.get("schema_version")) is int and data["schema_version"] == 1,
        "unsupported result schema",
    )
    for key, expected in {
        "run_id": run_id,
        "scenario_id": scenario["id"],
        "source_sha": source_sha,
        "tier": scenario["tier"],
    }.items():
        require(data.get(key) == expected, f"result {key} mismatch")
    require(
        data.get("status") in {"passed", "failed", "blocked"}, "invalid result status"
    )
    if data["status"] != "passed":
        return {"status": data["status"], "artifact_hashes": {}}
    checks = data.get("checks")
    require(isinstance(checks, list) and bool(checks), "checks required")
    identities = []
    for check in checks:
        require(
            isinstance(check, dict) and check.get("passed") is True,
            "check failed, skipped, or not boolean",
        )
        identities.append(check.get("id"))
    unique_strings(identities, "result checks", identifiers=False)
    require(set(scenario["checks"]) <= set(identities), "required checks missing")
    require(
        isinstance(data.get("cleanup"), dict) and data["cleanup"].get("passed") is True,
        "cleanup did not pass",
    )
    for key in ("skipped", "failures", "errors"):
        require(data.get(key, 0) == 0, "nonzero skipped or failed tests")
    hashes = artifact_hashes(root, data.get("artifacts"))
    return {"status": "passed", "artifact_hashes": hashes}
