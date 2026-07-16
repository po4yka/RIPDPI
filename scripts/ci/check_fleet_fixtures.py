#!/usr/bin/env python3
"""CI gate: provenance and structural checks for deploy fleet fixtures.

The golden-file suite ``FleetCompatGoldenFileTest`` parses committed
fixtures under ``core/data/src/test/resources/fleet-fixtures/``. The executable
cross-repo gate checks out the sibling ``ripdpi-vpn-deploy`` repo at the pin in
``scripts/fleet-fixtures/deployer-git-sha.txt`` and regenerates the supported
scenarios with frozen synthetic inputs. This checker validates the committed
fixtures and their provenance before that emitter gate runs.

It checks that:

* every required scenario directory exists with its required files;
* every ``bundle.json`` / ``expected-*.json`` / ``meta.json`` is valid JSON
  with the expected top-level shape;
* ``meta.json.deployer_git_sha`` is identical across all scenarios AND equals
  the pinned real commit in ``scripts/fleet-fixtures/deployer-git-sha.txt`` --
  bumping one without the other is the drift signal;
* no production-token shapes leak into any fixture (synthetic UUIDs only,
  RFC-5737 doc IPs only, ``-fixture`` keys).

Run as a CI gate::

    python3 scripts/ci/check_fleet_fixtures.py

Exit code is 0 when the committed fixtures are well-formed and consistent;
non-zero otherwise.
"""
from __future__ import annotations

import argparse
import ipaddress
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FIXTURES_ROOT = ROOT / "core/data/src/test/resources/fleet-fixtures"
PIN_FILE = ROOT / "scripts/fleet-fixtures/deployer-git-sha.txt"

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

EMITTED_SCENARIOS = frozenset(
    set(REQUIRED_SCENARIOS) - {"p2a-hysteria-port-hop"}
)

# Files every scenario must carry.
REQUIRED_FILES = ("bundle.json", "expected-profiles.json", "meta.json")

# Scenarios whose harness path additionally asserts a selector/urltest group.
# The port-hop negative scenario intentionally has no selector/urltest group.
SCENARIOS_WITH_GROUP = frozenset(REQUIRED_SCENARIOS) - {"p2a-hysteria-port-hop"}

# Scenarios whose harness path additionally asserts per-app routing rules.
SCENARIOS_WITH_ROUTING = frozenset({"per-app-bypass-and-via-tun"})

_GIT_SHA_RE = re.compile(r"^[0-9a-f]{40}$")

_FIXTURE_UUID_RE = re.compile(
    r"^00000000-0000-0000-0000-000000000[0-9a-f]{3}$"
)
_FIXTURE_PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
_CREDENTIAL_FIELDS = frozenset(
    {
        "auth",
        "auth_str",
        "password",
        "preshared_key",
        "private_key",
        "public_key",
        "reality_public_key",
        "server_public_key",
        "short_id",
        "token",
        "uuid",
    }
)
_DOCUMENTATION_NETWORKS = tuple(
    ipaddress.ip_network(cidr)
    for cidr in ("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24")
)


def pinned_sha(pin_file: Path = PIN_FILE) -> str:
    """Returns the exact deployer commit accepted by the cross-repo gate."""
    if not pin_file.is_file():
        raise ValueError(f"deployer pin file not found: {pin_file}")
    sha = pin_file.read_text(encoding="utf-8").strip()
    if not _GIT_SHA_RE.fullmatch(sha) or sha == "0" * 40:
        raise ValueError(
            f"{pin_file.name} must contain one non-zero 40-character lowercase git SHA"
        )
    return sha


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


def _check_meta_shape(data: object, label: str) -> tuple[str, str]:
    if not isinstance(data, dict):
        raise ValueError(f"{label}: meta.json must be a JSON object")
    sha = data.get("deployer_git_sha")
    if not isinstance(sha, str) or not sha:
        raise ValueError(f"{label}: meta.json must carry a string 'deployer_git_sha'")
    provenance = data.get("provenance")
    if provenance not in {"emitted", "parser-reference"}:
        raise ValueError(
            f"{label}: meta.json provenance must be 'emitted' or 'parser-reference'"
        )
    expected = "emitted" if label in EMITTED_SCENARIOS else "parser-reference"
    if provenance != expected:
        raise ValueError(
            f"{label}: provenance {provenance!r} does not match expected {expected!r}"
        )
    reference_reason = data.get("reference_reason")
    if provenance == "parser-reference" and (
        not isinstance(reference_reason, str) or not reference_reason.strip()
    ):
        raise ValueError(
            f"{label}: parser-reference metadata must carry a non-empty "
            "reference_reason"
        )
    return sha, provenance


def _walk_fields(value: object, path: str = ""):
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = f"{path}.{key}" if path else key
            yield child_path, key, child
            yield from _walk_fields(child, child_path)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from _walk_fields(child, f"{path}[{index}]")


def _check_no_production_tokens(bundle: object, label: str) -> None:
    for path, key, value in _walk_fields(bundle):
        if key not in _CREDENTIAL_FIELDS or not isinstance(value, str):
            continue
        if key == "uuid":
            is_fixture = bool(_FIXTURE_UUID_RE.fullmatch(value))
        else:
            is_fixture = "fixture" in value.lower() or value == _FIXTURE_PUBLIC_KEY
        if not is_fixture:
            raise ValueError(
                f"{label}: production-token shape detected at {path}; "
                "fixture credentials must carry an explicit synthetic marker"
            )

    if not isinstance(bundle, dict):
        return
    outbounds = bundle.get("outbounds")
    if not isinstance(outbounds, list):
        return
    for index, outbound in enumerate(outbounds):
        if not isinstance(outbound, dict):
            continue
        server = outbound.get("server")
        if not isinstance(server, str):
            continue
        try:
            address = ipaddress.ip_address(server)
        except ValueError:
            is_fixture = server.endswith(".example") or "fixture" in server.lower()
        else:
            is_fixture = any(address in network for network in _DOCUMENTATION_NETWORKS)
        if not is_fixture:
            raise ValueError(
                f"{label}: production-token shape detected at "
                f"outbounds[{index}].server; fixture endpoints must use "
                "RFC-5737 addresses or explicit fixture hostnames"
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
        _check_no_production_tokens(bundle, bundle_label)

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
        sha, _ = _check_meta_shape(meta, scenario)
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
            f"{PIN_FILE.name} ({expected_sha!r}); regenerate the fixtures "
            "with scripts/refresh-fleet-fixtures.sh --write or correct the pin"
        )

    return {
        "scenarios": sorted(REQUIRED_SCENARIOS),
        "deployerGitSha": actual_sha,
        "emittedScenarios": sorted(EMITTED_SCENARIOS),
        "parserReferenceScenarios": sorted(set(REQUIRED_SCENARIOS) - EMITTED_SCENARIOS),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--fixtures-root",
        default=str(FIXTURES_ROOT),
        help="Path to the fleet-fixtures directory.",
    )
    parser.add_argument(
        "--pin-file",
        default=str(PIN_FILE),
        help="Path to the file containing the exact deployer commit SHA.",
    )
    args = parser.parse_args(argv)

    pin_file = Path(args.pin_file)
    fixtures_root = Path(args.fixtures_root)

    expected_sha = pinned_sha(pin_file)
    summary = validate_fixtures(fixtures_root, expected_sha)

    print("Fleet fixtures structural check")
    print(f"Fixtures root: {fixtures_root}")
    print(f"Scenarios: {', '.join(summary['scenarios'])}")
    print(f"Emitted scenarios: {', '.join(summary['emittedScenarios'])}")
    print(
        "Parser-reference scenarios: "
        + ", ".join(summary["parserReferenceScenarios"])
    )
    print(f"Pinned deployer git SHA: {summary['deployerGitSha']}")
    print("ok: all fleet fixtures are well-formed and consistent")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"Fleet fixtures check failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
