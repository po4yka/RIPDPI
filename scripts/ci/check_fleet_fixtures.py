#!/usr/bin/env python3
"""CI gate: structural drift check for the ripdpi-vpn-deploy fleet fixtures.

The golden-file suite ``FleetCompatGoldenFileTest`` parses hand-authored
fixtures under ``core/data/src/test/resources/fleet-fixtures/`` that mirror the
output of the sibling ``ripdpi-vpn-deploy`` repo's ``emit-singbox.sh``. The
emitter needs Terraform + SOPS + real infra, so it cannot run in CI; this gate
validates the *committed* fixtures structurally instead -- no deployer, no
infra required.

It checks that:

* every required scenario directory exists with its required files;
* every ``bundle.json`` / ``expected-*.json`` / ``meta.json`` is valid JSON
  with the expected top-level shape;
* ``meta.json.deployer_git_sha`` is identical across all scenarios AND equals
  the pinned SHA declared in ``scripts/refresh-fleet-fixtures.sh`` -- bumping
  one without the other is the drift signal;
* no production-token shapes leak into any fixture (synthetic UUIDs only,
  RFC-5737 doc IPs only, ``-fixture`` keys).

Run as a CI gate::

    python3 scripts/ci/check_fleet_fixtures.py

Exit code is 0 when the committed fixtures are well-formed and consistent;
non-zero otherwise.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FIXTURES_ROOT = ROOT / "core/data/src/test/resources/fleet-fixtures"
REFRESH_SCRIPT = ROOT / "scripts/refresh-fleet-fixtures.sh"

# The scenario set the harness (FleetCompatGoldenFileTest) iterates. Keep this
# in lockstep with the `scenarios` list in that test.
REQUIRED_SCENARIOS = (
    "p0-only",
    "p1-only",
    "p2a-hysteria-only",
    "p2a-hysteria-port-hop",
    "multi-cohort-p0-p1-p2a",
    "multi-host-failover",
    "per-app-bypass-and-via-tun",
    "bootstrap-bundle",
)

# Files every scenario must carry.
REQUIRED_FILES = ("bundle.json", "expected-profiles.json", "meta.json")

# Scenarios whose harness path additionally asserts a selector/urltest group.
# Every committed scenario currently carries expected-group.json.
SCENARIOS_WITH_GROUP = frozenset(REQUIRED_SCENARIOS)

# Scenarios whose harness path additionally asserts per-app routing rules.
SCENARIOS_WITH_ROUTING = frozenset({"per-app-bypass-and-via-tun"})

# The line in refresh-fleet-fixtures.sh that pins the deployer SHA, e.g.
#   DEPLOYER_GIT_SHA="0000000000000000000000000000000000000000-fixture"
_PIN_RE = re.compile(r'^DEPLOYER_GIT_SHA="([^"]+)"', re.MULTILINE)

# A real-looking UUID that is NOT the frozen all-zero / -fixture pattern. This
# mirrors the guard in FleetCompatHarness.findProductionTokenShapes.
_PROD_UUID_RE = re.compile(
    r'"uuid"\s*:\s*"(?!0{8}-0{4}-0{4}-0{4}-0{11}[0-9a-f])'
    r'[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-'
    r'[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"'
)


def pinned_sha(refresh_script: Path = REFRESH_SCRIPT) -> str:
    """Returns the deployer git SHA pinned in refresh-fleet-fixtures.sh."""
    if not refresh_script.is_file():
        raise ValueError(f"refresh script not found: {refresh_script}")
    match = _PIN_RE.search(refresh_script.read_text(encoding="utf-8"))
    if not match:
        raise ValueError(
            f"{refresh_script.name} does not declare a "
            'DEPLOYER_GIT_SHA="..." pin line'
        )
    return match.group(1)


def _load_json(path: Path, label: str) -> object:
    text = path.read_text(encoding="utf-8")
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise ValueError(f"{label} is not valid JSON: {exc}") from exc


def _check_bundle_shape(data: object, label: str) -> None:
    if not isinstance(data, dict):
        raise ValueError(f"{label}: bundle.json must be a JSON object")
    outbounds = data.get("outbounds")
    if not isinstance(outbounds, list) or not outbounds:
        raise ValueError(f"{label}: bundle.json must carry a non-empty 'outbounds' array")


def _check_profiles_shape(data: object, label: str) -> None:
    if not isinstance(data, list):
        raise ValueError(f"{label}: expected-profiles.json must be a JSON array")


def _check_group_shape(data: object, label: str) -> None:
    if not isinstance(data, dict):
        raise ValueError(f"{label}: expected-group.json must be a JSON object")


def _check_routing_shape(data: object, label: str) -> None:
    if not isinstance(data, list):
        raise ValueError(f"{label}: expected-routing.json must be a JSON array")


def _check_meta_shape(data: object, label: str) -> str:
    if not isinstance(data, dict):
        raise ValueError(f"{label}: meta.json must be a JSON object")
    sha = data.get("deployer_git_sha")
    if not isinstance(sha, str) or not sha:
        raise ValueError(f"{label}: meta.json must carry a string 'deployer_git_sha'")
    return sha


def _check_no_production_tokens(text: str, label: str) -> None:
    offenders = _PROD_UUID_RE.findall(text)
    if offenders:
        raise ValueError(
            f"{label}: production-token shape(s) detected "
            f"(fixtures must use frozen synthetic values): {offenders}"
        )


def validate_fixtures(fixtures_root: Path, expected_sha: str) -> dict:
    """Validates the committed fleet fixtures. Raises ValueError on any defect."""
    if not fixtures_root.is_dir():
        raise ValueError(f"fixtures root not found: {fixtures_root}")

    seen_shas: dict[str, str] = {}

    for scenario in REQUIRED_SCENARIOS:
        scenario_dir = fixtures_root / scenario
        if not scenario_dir.is_dir():
            raise ValueError(f"missing scenario directory: {scenario}")

        required = list(REQUIRED_FILES)
        if scenario in SCENARIOS_WITH_GROUP:
            required.append("expected-group.json")
        if scenario in SCENARIOS_WITH_ROUTING:
            required.append("expected-routing.json")

        for filename in required:
            path = scenario_dir / filename
            if not path.is_file():
                raise ValueError(f"missing {scenario}/{filename}")

        # bundle.json
        bundle_path = scenario_dir / "bundle.json"
        bundle_label = f"{scenario}/bundle.json"
        bundle = _load_json(bundle_path, bundle_label)
        _check_bundle_shape(bundle, scenario)
        _check_no_production_tokens(
            bundle_path.read_text(encoding="utf-8"), bundle_label
        )

        # expected-profiles.json
        profiles_label = f"{scenario}/expected-profiles.json"
        profiles = _load_json(scenario_dir / "expected-profiles.json", profiles_label)
        _check_profiles_shape(profiles, scenario)

        # expected-group.json (optional per harness, present for all today)
        group_path = scenario_dir / "expected-group.json"
        if group_path.is_file():
            group_label = f"{scenario}/expected-group.json"
            _check_group_shape(_load_json(group_path, group_label), scenario)

        # expected-routing.json (optional per harness)
        routing_path = scenario_dir / "expected-routing.json"
        if routing_path.is_file():
            routing_label = f"{scenario}/expected-routing.json"
            _check_routing_shape(_load_json(routing_path, routing_label), scenario)

        # meta.json
        meta_label = f"{scenario}/meta.json"
        meta = _load_json(scenario_dir / "meta.json", meta_label)
        sha = _check_meta_shape(meta, scenario)
        seen_shas[scenario] = sha

    # deployer_git_sha must be identical across scenarios AND match the pin.
    distinct = sorted(set(seen_shas.values()))
    if len(distinct) != 1:
        detail = ", ".join(f"{s}={seen_shas[s]}" for s in sorted(seen_shas))
        raise ValueError(
            "meta.json deployer_git_sha is inconsistent across scenarios: " + detail
        )
    actual_sha = distinct[0]
    if actual_sha != expected_sha:
        raise ValueError(
            "meta.json deployer_git_sha "
            f"({actual_sha!r}) does not match the pin in "
            f"{REFRESH_SCRIPT.name} ({expected_sha!r}); regenerate the fixtures "
            "with scripts/refresh-fleet-fixtures.sh --write or correct the pin"
        )

    return {
        "scenarios": sorted(REQUIRED_SCENARIOS),
        "deployerGitSha": actual_sha,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--fixtures-root",
        default=str(FIXTURES_ROOT),
        help="Path to the fleet-fixtures directory.",
    )
    parser.add_argument(
        "--refresh-script",
        default=str(REFRESH_SCRIPT),
        help="Path to scripts/refresh-fleet-fixtures.sh (source of the SHA pin).",
    )
    args = parser.parse_args(argv)

    refresh_script = Path(args.refresh_script)
    fixtures_root = Path(args.fixtures_root)

    expected_sha = pinned_sha(refresh_script)
    summary = validate_fixtures(fixtures_root, expected_sha)

    print("Fleet fixtures structural check")
    print(f"Fixtures root: {fixtures_root}")
    print(f"Scenarios: {', '.join(summary['scenarios'])}")
    print(f"Pinned deployer git SHA: {summary['deployerGitSha']}")
    print("ok: all fleet fixtures are well-formed and consistent")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"Fleet fixtures check failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
