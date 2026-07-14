#!/usr/bin/env python3
"""Validate the cross-language runtime contract gate inventory."""
from __future__ import annotations

import argparse
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
POLICY_PATH = ROOT / "quality/release-gates/cross-language-runtime-contracts.json"
EXPECTED_VERSION = "cross_language_runtime_contracts_v1"

REQUIRED_SURFACES = {
    "diagnostics-json-wire",
    "app-settings-protobuf",
    "native-config-json",
    "native-jni-behavior",
    "runtime-telemetry-json",
}

REQUIRED_GATE_IDS = {
    "diagnostics-json-wire": {
        "rust-diagnostics-contract-fixtures",
        "kotlin-diagnostics-wire-contract",
        "kotlin-diagnostics-governance-contract",
    },
    "app-settings-protobuf": {
        "kotlin-app-settings-json-defaults",
        "kotlin-proxy-setting-feature-contract",
        "rust-proxy-setting-feature-contract",
    },
    "native-config-json": {
        "kotlin-native-config-round-trip",
        "kotlin-native-config-snapshot",
        "rust-proxy-config-round-trip",
    },
    "native-jni-behavior": {
        "kotlin-native-binary-contract",
        "kotlin-engine-api-contract-binding",
        "python-ffi-panic-boundary",
        "python-native-architecture-contracts",
    },
    "runtime-telemetry-json": {
        "kotlin-native-telemetry-goldens",
        "kotlin-service-telemetry-goldens",
        "rust-android-telemetry-contracts",
        "rust-tunnel-telemetry-contracts",
    },
}

REQUIRED_SOURCE_HINTS = {
    "diagnostics-json-wire": {"EngineContract.kt", "ripdpi-diagnostics-contracts", "contract_fixtures.rs"},
    "app-settings-protobuf": {"app_settings.proto", "AppSettingsSerializer.kt", "feature-contract-harness"},
    "native-config-json": {"ConfigContractRoundTripTest.kt", "ripdpi-proxy-config", "round_trip.rs"},
    "native-jni-behavior": {"JNI_CONTRACT.md", "ripdpi-android", "check_ffi_panic_boundary.py"},
    "runtime-telemetry-json": {"NativeRuntimeSnapshot.kt", "NativeTelemetryGoldenTest.kt", "ripdpi-android-telemetry-adapter"},
}

REQUIRED_TRIGGER_TERMS = {
    "field",
    "default",
}


def load_json(path: Path = POLICY_PATH) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _require_paths_exist(paths: object, *, surface_id: str, key: str) -> list[str]:
    if not isinstance(paths, list) or not paths:
        raise ValueError(f"surface {surface_id} must declare a non-empty {key} list")
    normalized: list[str] = []
    for value in paths:
        if not isinstance(value, str) or not value:
            raise ValueError(f"surface {surface_id} has an invalid {key} entry")
        if not (ROOT / value).exists():
            raise ValueError(f"surface {surface_id} references missing {key} path: {value}")
        normalized.append(value)
    return normalized


def _validate_gate_tests(surface_id: str, gate_tests: object) -> set[str]:
    if not isinstance(gate_tests, list) or not gate_tests:
        raise ValueError(f"surface {surface_id} must declare a non-empty gateTests list")
    seen: set[str] = set()
    for index, gate in enumerate(gate_tests):
        if not isinstance(gate, dict):
            raise ValueError(f"surface {surface_id} gateTests[{index}] must be an object")
        gate_id = gate.get("id")
        path = gate.get("path")
        command = gate.get("command")
        if not isinstance(gate_id, str) or not gate_id:
            raise ValueError(f"surface {surface_id} gateTests[{index}] is missing id")
        if gate_id in seen:
            raise ValueError(f"surface {surface_id} declares duplicate gate id: {gate_id}")
        if not isinstance(path, str) or not path:
            raise ValueError(f"surface {surface_id} gate {gate_id} is missing path")
        if not (ROOT / path).exists():
            raise ValueError(f"surface {surface_id} gate {gate_id} references missing path: {path}")
        if not isinstance(command, str) or not command.strip():
            raise ValueError(f"surface {surface_id} gate {gate_id} is missing command")
        if not any(token in command for token in ("cargo test --locked", "./gradlew", "python3")):
            raise ValueError(f"surface {surface_id} gate {gate_id} command must be an executable test/check")
        seen.add(gate_id)
    missing = REQUIRED_GATE_IDS[surface_id] - seen
    if missing:
        raise ValueError(f"surface {surface_id} is missing required gate ids: {', '.join(sorted(missing))}")
    return seen


def _validate_change_triggers(surface_id: str, triggers: object) -> list[str]:
    if not isinstance(triggers, list) or not triggers:
        raise ValueError(f"surface {surface_id} must declare changeTriggers")
    lowered = " ".join(str(trigger).lower() for trigger in triggers)
    missing = {term for term in REQUIRED_TRIGGER_TERMS if term not in lowered}
    if surface_id in {"diagnostics-json-wire", "app-settings-protobuf"} and "schema" not in lowered:
        missing.add("schema")
    if surface_id == "native-jni-behavior" and "jni" not in lowered:
        missing.add("jni")
    if missing:
        raise ValueError(f"surface {surface_id} changeTriggers must mention: {', '.join(sorted(missing))}")
    return [str(trigger) for trigger in triggers]


def _validate_surface(surface: dict) -> dict:
    surface_id = surface.get("id")
    if surface_id not in REQUIRED_SURFACES:
        raise ValueError(f"unknown or missing surface id: {surface_id!r}")
    if not isinstance(surface.get("description"), str) or not surface["description"].strip():
        raise ValueError(f"surface {surface_id} must describe the contract")
    source_paths = _require_paths_exist(surface.get("sourcePaths"), surface_id=surface_id, key="sourcePaths")
    fixture_paths = _require_paths_exist(surface.get("fixturePaths"), surface_id=surface_id, key="fixturePaths")
    gate_ids = _validate_gate_tests(surface_id, surface.get("gateTests"))
    _validate_change_triggers(surface_id, surface.get("changeTriggers"))
    invariants = surface.get("invariants")
    if not isinstance(invariants, list) or len(invariants) < 2:
        raise ValueError(f"surface {surface_id} must declare at least two invariants")
    joined_sources = "\n".join(source_paths)
    missing_hints = {hint for hint in REQUIRED_SOURCE_HINTS[surface_id] if hint not in joined_sources}
    if missing_hints:
        raise ValueError(f"surface {surface_id} sourcePaths are missing expected hints: {', '.join(sorted(missing_hints))}")
    if not any("fixture" in path or "golden" in path or "contract" in path for path in fixture_paths):
        raise ValueError(f"surface {surface_id} must include committed fixture/golden paths")
    return {
        "id": surface_id,
        "gateCount": len(gate_ids),
        "sourceCount": len(source_paths),
        "fixtureCount": len(fixture_paths),
        "invariantCount": len(invariants),
    }


def validate_policy(policy: dict) -> dict:
    if policy.get("version") != EXPECTED_VERSION:
        raise ValueError(f"unexpected policy version: {policy.get('version')!r} (expected {EXPECTED_VERSION!r})")
    surfaces = policy.get("surfaces")
    if not isinstance(surfaces, list) or not surfaces:
        raise ValueError("policy must declare a non-empty surfaces list")
    summaries: list[dict] = []
    seen: set[str] = set()
    for surface in surfaces:
        if not isinstance(surface, dict):
            raise ValueError("every surface entry must be an object")
        summary = _validate_surface(surface)
        surface_id = summary["id"]
        if surface_id in seen:
            raise ValueError(f"duplicate surface id: {surface_id}")
        seen.add(surface_id)
        summaries.append(summary)
    missing = REQUIRED_SURFACES - seen
    if missing:
        raise ValueError("policy is missing required surfaces: " + ", ".join(sorted(missing)))
    return {
        "version": policy["version"],
        "surfaceCount": len(summaries),
        "gateCount": sum(item["gateCount"] for item in summaries),
        "surfaces": summaries,
    }


def render_report(summary: dict) -> str:
    lines = [
        "# Cross-language runtime contract gates",
        "",
        f"- Policy version: `{summary['version']}`",
        f"- Surfaces: {summary['surfaceCount']}",
        f"- Gates: {summary['gateCount']}",
        "",
        "| Surface | Gates | Sources | Fixtures | Invariants |",
        "|---------|-------|---------|----------|------------|",
    ]
    for surface in summary["surfaces"]:
        lines.append(
            f"| `{surface['id']}` | {surface['gateCount']} | {surface['sourceCount']} | "
            f"{surface['fixtureCount']} | {surface['invariantCount']} |"
        )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--policy", default=str(POLICY_PATH), help="Path to the cross-language runtime contract policy JSON.")
    parser.add_argument("--report", action="store_true", help="Print a markdown summary instead of a terse status line.")
    args = parser.parse_args(argv)
    policy = load_json(Path(args.policy))
    summary = validate_policy(policy)
    if args.report:
        print(render_report(summary))
    else:
        print(f"Cross-language runtime contract gates valid: {summary['surfaceCount']} surfaces, {summary['gateCount']} gates")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
