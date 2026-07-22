#!/usr/bin/env python3
"""Build and validate redacted dual-vantage network release evidence.

The published bundle contains only this manifest and two strict packet-summary
documents. Raw captures stay private; their SHA-256 digests bind the summaries
to the source captures without publishing addresses or payloads.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
import time
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
POLICY_PATH = ROOT / "quality/release-gates/dns-ipv6-killswitch-gates.json"
PRODUCER_POLICY_PATH = ROOT / "quality/release-gates/network-evidence-producers.json"
RUNNER_PATH = ROOT / "test-lab/scripts/run-dual-vantage-network-evidence.sh"

MANIFEST_VERSION = "network_evidence_manifest_v2"
OBSERVATION_VERSION = "network_evidence_observation_v2"
PLAN_VERSION = "network_evidence_scenario_plan_v2"
ALLOWED_APPLIES_TO = {"android-client-release", "fleet-profile-rollout"}
REQUIRED_ROLES = ("client-underlay", "external-observer")
ROLE_PATHS = {
    "client-underlay": "client-observation.json",
    "external-observer": "observer-observation.json",
}
ALLOWED_KINDS = {"dns", "ipv6", "direct_window"}
SHA1_RE = re.compile(r"[0-9a-f]{40}")
SHA256_RE = re.compile(r"[0-9a-f]{64}")
IDENTIFIER_RE = re.compile(r"[a-z0-9][a-z0-9_-]{0,127}")
MAX_CLOCK_SKEW_SECONDS = 60
EVIDENCE_WORKFLOW_PATH = ".github/workflows/dns-ipv6-killswitch-evidence.yml"
ALLOWED_EXECUTION_KINDS = {"github-actions", "local"}

OBSERVATION_FIELDS = {
    "version",
    "sourceSha",
    "correlationId",
    "role",
    "vantageIdSha256",
    "networkIdSha256",
    "collectorSha256",
    "clientArtifactSha256",
    "scenarioPlanSha256",
    "captureStartedAtEpoch",
    "captureFinishedAtEpoch",
    "rawCaptureSha256",
    "windows",
}
UNSTAMPED_OBSERVATION_FIELDS = OBSERVATION_FIELDS - {
    "vantageIdSha256",
    "networkIdSha256",
    "collectorSha256",
}
WINDOW_FIELDS = {
    "id",
    "kind",
    "startedAtEpoch",
    "finishedAtEpoch",
    "expectedPacketCount",
    "unexpectedPacketCount",
    "captureErrorCount",
    "actionMarkerSha256",
    "outcomeMarkerSha256",
    "actionObservedCount",
    "outcomeObservedCount",
}
PLAN_FIELDS = {
    "version",
    "sourceSha",
    "correlationId",
    "clientArtifactSha256",
    "windows",
}
PLAN_WINDOW_FIELDS = {
    "id",
    "kind",
    "startedAtEpoch",
    "finishedAtEpoch",
    "actionMarkerSha256",
    "outcomeMarkerSha256",
}
MANIFEST_FIELDS = {
    "version",
    "sourceSha",
    "appliesTo",
    "correlationId",
    "generatedAtEpoch",
    "provenance",
    "artifacts",
    "scenarios",
    "gateResults",
}
PROVENANCE_FIELDS = {
    "executionKind",
    "executionId",
    "executionAttempt",
    "executionDefinition",
    "runnerSha256",
    "validatorSha256",
    "policySha256",
    "producerPolicySha256",
    "scenarioPlanSha256",
    "workloadSha256",
    "clientArtifactSha256",
}
ARTIFACT_FIELDS = {
    "role",
    "path",
    "sha256",
    "rawCaptureSha256",
    "vantageIdSha256",
    "networkIdSha256",
    "collectorSha256",
}
SCENARIO_FIELDS = {
    "id",
    "kind",
    "startedAtEpoch",
    "finishedAtEpoch",
    "actionMarkerSha256",
    "outcomeMarkerSha256",
}


def canonical_json_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=True, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def write_canonical_json(path: Path, value: Any) -> None:
    serialized = canonical_json_bytes(value)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".tmp",
    )
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(serialized)
        os.replace(temporary_path, path)
    except BaseException:
        temporary_path.unlink(missing_ok=True)
        raise


def paths_alias(left: Path, right: Path) -> bool:
    """Compare existing paths by inode, falling back only for missing paths."""
    try:
        return os.path.samefile(left, right)
    except FileNotFoundError:
        pass
    except OSError as exc:
        raise ValueError("cannot safely compare network evidence paths") from exc
    try:
        return left.resolve(strict=False) == right.resolve(strict=False)
    except OSError as exc:
        raise ValueError("cannot safely compare network evidence paths") from exc


def prepare_cli_output(path: Path | None, *, inputs: tuple[Path, ...]) -> None:
    """Invalidate a stale CLI target without ever deleting one of its inputs."""
    if path is None:
        return
    if any(paths_alias(path, input_path) for input_path in inputs):
        raise ValueError("network evidence output path must differ from input paths")
    path.unlink(missing_ok=True)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def derive_marker(
    correlation_id: str, gate_id: str, kind: str, purpose: str
) -> str:
    payload = (
        "ripdpi:network-evidence-marker:v2:"
        f"{correlation_id}:{gate_id}:{kind}:{purpose}"
    ).encode("ascii")
    return sha256_bytes(payload)


def load_producer_policy() -> dict[str, Any]:
    value = json.loads(PRODUCER_POLICY_PATH.read_text(encoding="utf-8"))
    fields = {
        "version",
        "clientCollectorSha256",
        "observerCollectorSha256",
        "workloadSha256",
        "clientArtifactSha256",
    }
    if not isinstance(value, dict):
        raise ValueError("producer policy must be a JSON object")
    require_exact_fields(value, fields, "producer policy")
    if value["version"] != "network_evidence_producers_v1":
        raise ValueError("unsupported producer policy version")
    for field in fields - {"version"}:
        entries = value[field]
        if not isinstance(entries, list) or len(entries) != len(set(entries)):
            raise ValueError(f"producer policy {field} must be a unique array")
        for index, entry in enumerate(entries):
            require_pattern(entry, SHA256_RE, f"producer policy {field}[{index}]")
    return value


def enforce_producer_policy(
    *,
    client_collector_sha256: str,
    observer_collector_sha256: str,
    workload_sha256: str,
    client_artifact_sha256: str,
) -> None:
    policy = load_producer_policy()
    memberships = (
        (client_collector_sha256, "clientCollectorSha256", "client collector"),
        (observer_collector_sha256, "observerCollectorSha256", "observer collector"),
        (workload_sha256, "workloadSha256", "workload"),
        (client_artifact_sha256, "clientArtifactSha256", "client artifact"),
    )
    for digest, field, label in memberships:
        if digest not in policy[field]:
            raise ValueError(f"{label} digest is not approved by producer policy")


def load_json_bytes(path: Path) -> tuple[dict[str, Any], bytes]:
    raw = path.read_bytes()
    value = json.loads(raw.decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path.name} must contain a JSON object")
    return value, raw


def required_gate_ids(*, applies_to: str = "android-client-release") -> set[str]:
    policy = json.loads(POLICY_PATH.read_text(encoding="utf-8"))
    policy_scopes = policy.get("appliesTo", [])
    return {
        gate["id"]
        for gate in policy["gates"]
        if gate.get("evidenceSources", {}).get(
            applies_to, gate.get("evidenceSource")
        )
        == "dual-vantage-network-manifest"
        and applies_to in gate.get("appliesTo", policy_scopes)
    }


def expected_kind(gate_id: str) -> str:
    if gate_id.startswith(("dns-", "synthetic-")):
        return "dns"
    if "ipv6" in gate_id or gate_id.startswith(("ipv4only-", "dualstack-")):
        return "ipv6"
    return "direct_window"


def require_exact_fields(
    value: dict[str, Any], allowed: set[str], context: str
) -> None:
    unknown = sorted(set(value) - allowed)
    missing = sorted(allowed - set(value))
    if unknown:
        raise ValueError(f"{context} has unknown fields: {', '.join(unknown)}")
    if missing:
        raise ValueError(f"{context} is missing fields: {', '.join(missing)}")


def require_int(value: Any, context: str, *, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ValueError(f"{context} must be an integer >= {minimum}")
    return value


def require_pattern(value: Any, pattern: re.Pattern[str], context: str) -> str:
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise ValueError(f"{context} has invalid format")
    return value


def validate_plan(
    value: Any,
    *,
    expected_source_sha: str,
    expected_correlation_id: str,
    expected_client_artifact_sha256: str,
    applies_to: str,
) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError("scenario plan must be a JSON object")
    require_exact_fields(value, PLAN_FIELDS, "scenario plan")
    if value["version"] != PLAN_VERSION:
        raise ValueError(f"unexpected scenario plan version: {value['version']!r}")
    if value["sourceSha"] != expected_source_sha:
        raise ValueError("scenario plan sourceSha does not match runner sourceSha")
    if value["correlationId"] != expected_correlation_id:
        raise ValueError("scenario plan correlationId does not match runner correlationId")
    if value["clientArtifactSha256"] != expected_client_artifact_sha256:
        raise ValueError("scenario plan client artifact digest does not match runner")
    windows = value["windows"]
    if not isinstance(windows, list) or not windows:
        raise ValueError("scenario plan windows must be a non-empty array")
    seen_ids: set[str] = set()
    all_markers: set[str] = set()
    for index, window in enumerate(windows):
        context = f"scenario plan.windows[{index}]"
        if not isinstance(window, dict):
            raise ValueError(f"{context} must be an object")
        require_exact_fields(window, PLAN_WINDOW_FIELDS, context)
        gate_id = require_pattern(window["id"], IDENTIFIER_RE, f"{context}.id")
        if gate_id not in required_gate_ids(applies_to=applies_to):
            raise ValueError(f"{context}.id is not a dual-vantage gate")
        if gate_id in seen_ids:
            raise ValueError(f"scenario plan has duplicate gate id: {gate_id}")
        seen_ids.add(gate_id)
        if window["kind"] != expected_kind(gate_id):
            raise ValueError(f"{context}.kind does not match release gate")
        started = require_int(window["startedAtEpoch"], f"{context}.startedAtEpoch", minimum=1)
        finished = require_int(window["finishedAtEpoch"], f"{context}.finishedAtEpoch", minimum=1)
        if finished <= started:
            raise ValueError(f"{context} must have a positive duration")
        action = require_pattern(
            window["actionMarkerSha256"], SHA256_RE, f"{context}.actionMarkerSha256"
        )
        outcome = require_pattern(
            window["outcomeMarkerSha256"], SHA256_RE, f"{context}.outcomeMarkerSha256"
        )
        expected_action = derive_marker(
            expected_correlation_id, gate_id, window["kind"], "action"
        )
        expected_outcome = derive_marker(
            expected_correlation_id, gate_id, window["kind"], "outcome"
        )
        if action != expected_action or outcome != expected_outcome:
            raise ValueError("scenario plan markers must match their correlation and gate")
        if action in all_markers or outcome in all_markers or action == outcome:
            raise ValueError("scenario plan markers must be globally unique")
        all_markers.update((action, outcome))
    expected_ids = required_gate_ids(applies_to=applies_to)
    if seen_ids != expected_ids:
        raise ValueError(
            "scenario plan gate ids do not match policy; "
            f"missing={sorted(expected_ids - seen_ids)}, extra={sorted(seen_ids - expected_ids)}"
        )
    return value


def validate_window(
    window: Any,
    *,
    context: str,
    capture_start: int,
    capture_finish: int,
    applies_to: str,
) -> dict[str, Any]:
    if not isinstance(window, dict):
        raise ValueError(f"{context} must be an object")
    require_exact_fields(window, WINDOW_FIELDS, context)
    gate_id = require_pattern(window["id"], IDENTIFIER_RE, f"{context}.id")
    if gate_id not in required_gate_ids(applies_to=applies_to):
        raise ValueError(
            f"{context}.id is not a dual-vantage release gate for {applies_to}"
        )
    kind = window["kind"]
    if kind not in ALLOWED_KINDS or kind != expected_kind(gate_id):
        raise ValueError(f"{context}.kind does not match release gate {gate_id}")
    started = require_int(
        window["startedAtEpoch"], f"{context}.startedAtEpoch", minimum=1
    )
    finished = require_int(
        window["finishedAtEpoch"], f"{context}.finishedAtEpoch", minimum=1
    )
    if finished <= started:
        raise ValueError(f"{context} must have a positive duration")
    if started < capture_start or finished > capture_finish:
        raise ValueError(f"{context} is not covered by the capture window")
    expected = require_int(
        window["expectedPacketCount"], f"{context}.expectedPacketCount"
    )
    if expected == 0:
        raise ValueError(f"{context} is missing a positive control packet")
    require_int(window["unexpectedPacketCount"], f"{context}.unexpectedPacketCount")
    require_int(window["captureErrorCount"], f"{context}.captureErrorCount")
    require_pattern(
        window["actionMarkerSha256"], SHA256_RE, f"{context}.actionMarkerSha256"
    )
    require_pattern(
        window["outcomeMarkerSha256"], SHA256_RE, f"{context}.outcomeMarkerSha256"
    )
    require_int(window["actionObservedCount"], f"{context}.actionObservedCount", minimum=1)
    require_int(
        window["outcomeObservedCount"], f"{context}.outcomeObservedCount", minimum=1
    )
    return window


def validate_observation(
    value: Any,
    *,
    expected_role: str | None = None,
    applies_to: str = "android-client-release",
) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError("observation must be a JSON object")
    require_exact_fields(value, OBSERVATION_FIELDS, "observation")
    if value["version"] != OBSERVATION_VERSION:
        raise ValueError(f"unexpected observation version: {value['version']!r}")
    require_pattern(value["sourceSha"], SHA1_RE, "observation.sourceSha")
    require_pattern(value["correlationId"], SHA256_RE, "observation.correlationId")
    role = value["role"]
    if role not in REQUIRED_ROLES or (
        expected_role is not None and role != expected_role
    ):
        raise ValueError(f"unexpected observation role: {role!r}")
    require_pattern(value["vantageIdSha256"], SHA256_RE, "observation.vantageIdSha256")
    require_pattern(value["networkIdSha256"], SHA256_RE, "observation.networkIdSha256")
    require_pattern(value["collectorSha256"], SHA256_RE, "observation.collectorSha256")
    require_pattern(
        value["clientArtifactSha256"],
        SHA256_RE,
        "observation.clientArtifactSha256",
    )
    require_pattern(
        value["scenarioPlanSha256"],
        SHA256_RE,
        "observation.scenarioPlanSha256",
    )
    capture_start = require_int(
        value["captureStartedAtEpoch"], "observation.captureStartedAtEpoch", minimum=1
    )
    capture_finish = require_int(
        value["captureFinishedAtEpoch"], "observation.captureFinishedAtEpoch", minimum=1
    )
    if capture_finish <= capture_start:
        raise ValueError("observation capture window must have a positive duration")
    require_pattern(
        value["rawCaptureSha256"], SHA256_RE, "observation.rawCaptureSha256"
    )
    windows = value["windows"]
    if not isinstance(windows, list) or not windows:
        raise ValueError("observation.windows must be a non-empty array")
    seen: set[str] = set()
    all_markers: set[str] = set()
    for index, window in enumerate(windows):
        validated = validate_window(
            window,
            context=f"observation.windows[{index}]",
            capture_start=capture_start,
            capture_finish=capture_finish,
            applies_to=applies_to,
        )
        gate_id = validated["id"]
        if gate_id in seen:
            raise ValueError(f"observation has duplicate window id: {gate_id}")
        seen.add(gate_id)
        action = validated["actionMarkerSha256"]
        outcome = validated["outcomeMarkerSha256"]
        expected_action = derive_marker(
            value["correlationId"], gate_id, validated["kind"], "action"
        )
        expected_outcome = derive_marker(
            value["correlationId"], gate_id, validated["kind"], "outcome"
        )
        if action != expected_action or outcome != expected_outcome:
            raise ValueError("observation markers must match their correlation and gate")
        if action in all_markers or outcome in all_markers or action == outcome:
            raise ValueError("observation markers must be globally unique")
        all_markers.update((action, outcome))
    expected_ids = required_gate_ids(applies_to=applies_to)
    if not expected_ids:
        raise ValueError(f"policy has no dual-vantage gates for {applies_to}")
    if seen != expected_ids:
        missing = sorted(expected_ids - seen)
        extra = sorted(seen - expected_ids)
        raise ValueError(
            f"observation window ids do not match policy; missing={missing}, extra={extra}"
        )
    return value


def stamp_observation(
    value: Any,
    *,
    plan: dict[str, Any],
    expected_role: str,
    expected_source_sha: str,
    expected_correlation_id: str,
    vantage_id_sha256: str,
    network_id_sha256: str,
    collector_sha256: str,
    client_artifact_sha256: str,
    applies_to: str = "android-client-release",
) -> dict[str, Any]:
    """Bind a hook summary to runner-owned collector, vantage, and network IDs."""
    if not isinstance(value, dict):
        raise ValueError("unstamped observation must be a JSON object")
    require_exact_fields(value, UNSTAMPED_OBSERVATION_FIELDS, "unstamped observation")
    require_pattern(expected_source_sha, SHA1_RE, "expectedSourceSha")
    require_pattern(expected_correlation_id, SHA256_RE, "expectedCorrelationId")
    require_pattern(vantage_id_sha256, SHA256_RE, "vantageIdSha256")
    require_pattern(network_id_sha256, SHA256_RE, "networkIdSha256")
    require_pattern(collector_sha256, SHA256_RE, "collectorSha256")
    require_pattern(client_artifact_sha256, SHA256_RE, "clientArtifactSha256")
    if value["role"] != expected_role:
        raise ValueError("unstamped observation role does not match runner role")
    if value["sourceSha"] != expected_source_sha:
        raise ValueError(
            "unstamped observation sourceSha does not match runner sourceSha"
        )
    if value["correlationId"] != expected_correlation_id:
        raise ValueError(
            "unstamped observation correlationId does not match runner correlationId"
        )
    if value["clientArtifactSha256"] != client_artifact_sha256:
        raise ValueError(
            "unstamped observation clientArtifactSha256 does not match runner artifact"
        )
    plan_sha256 = sha256_bytes(canonical_json_bytes(plan))
    if value["scenarioPlanSha256"] != plan_sha256:
        raise ValueError("unstamped observation scenarioPlanSha256 does not match plan")
    plan_windows = {window["id"]: window for window in plan["windows"]}
    observation_windows = {window["id"]: window for window in value["windows"]}
    if set(plan_windows) != set(observation_windows):
        raise ValueError("unstamped observation windows do not match scenario plan")
    for gate_id, plan_window in plan_windows.items():
        observation_window = observation_windows[gate_id]
        for field in PLAN_WINDOW_FIELDS:
            if observation_window[field] != plan_window[field]:
                raise ValueError(
                    f"unstamped observation window does not match plan for {gate_id}"
                )
    stamped = dict(value)
    stamped["vantageIdSha256"] = vantage_id_sha256
    stamped["networkIdSha256"] = network_id_sha256
    stamped["collectorSha256"] = collector_sha256
    return validate_observation(
        stamped, expected_role=expected_role, applies_to=applies_to
    )


def _window_map(observation: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {window["id"]: window for window in observation["windows"]}


def derive_bundle(
    client: dict[str, Any], observer: dict[str, Any]
) -> tuple[list[dict[str, Any]], dict[str, str]]:
    client_windows = _window_map(client)
    observer_windows = _window_map(observer)
    if set(client_windows) != set(observer_windows):
        raise ValueError("client and observer window ids do not match")
    scenarios: list[dict[str, Any]] = []
    gate_results: dict[str, str] = {}
    for gate_id in sorted(client_windows):
        left = client_windows[gate_id]
        right = observer_windows[gate_id]
        if left["kind"] != right["kind"]:
            raise ValueError(f"window kind mismatch for {gate_id}")
        for marker_field in ("actionMarkerSha256", "outcomeMarkerSha256"):
            if left[marker_field] != right[marker_field]:
                raise ValueError(f"window marker mismatch for {gate_id}")
        if (
            abs(left["startedAtEpoch"] - right["startedAtEpoch"])
            > MAX_CLOCK_SKEW_SECONDS
        ):
            raise ValueError(
                f"client and observer window clock skew exceeds limit for {gate_id}"
            )
        started = max(left["startedAtEpoch"], right["startedAtEpoch"])
        finished = min(left["finishedAtEpoch"], right["finishedAtEpoch"])
        if finished <= started:
            raise ValueError(
                f"client and observer windows do not overlap for {gate_id}"
            )
        scenarios.append(
            {
                "id": gate_id,
                "kind": left["kind"],
                "startedAtEpoch": started,
                "finishedAtEpoch": finished,
                "actionMarkerSha256": left["actionMarkerSha256"],
                "outcomeMarkerSha256": left["outcomeMarkerSha256"],
            }
        )
        has_unexpected_packets = any(
            window["unexpectedPacketCount"] > 0 for window in (left, right)
        )
        has_capture_errors = any(
            window["captureErrorCount"] > 0 for window in (left, right)
        )
        if has_unexpected_packets:
            gate_results[gate_id] = "FAIL"
        elif has_capture_errors:
            gate_results[gate_id] = "INCONCLUSIVE"
        else:
            gate_results[gate_id] = "PASS"
    return scenarios, gate_results


def assemble_manifest(
    *,
    client_path: Path,
    observer_path: Path,
    source_sha: str,
    applies_to: str,
    generated_at_epoch: int,
    execution_kind: str,
    execution_id: str,
    execution_attempt: int,
    execution_definition: str,
    runner_sha256: str,
    validator_sha256: str,
    policy_sha256: str,
    producer_policy_sha256: str,
    workload_sha256: str,
    client_artifact_sha256: str,
) -> dict[str, Any]:
    require_pattern(source_sha, SHA1_RE, "sourceSha")
    if applies_to not in ALLOWED_APPLIES_TO:
        raise ValueError(f"unsupported appliesTo: {applies_to!r}")
    require_int(generated_at_epoch, "generatedAtEpoch", minimum=1)
    if execution_kind not in ALLOWED_EXECUTION_KINDS:
        raise ValueError(f"unsupported executionKind: {execution_kind!r}")
    require_pattern(execution_id, IDENTIFIER_RE, "executionId")
    require_int(execution_attempt, "executionAttempt", minimum=1)
    if execution_kind == "github-actions":
        if execution_definition != EVIDENCE_WORKFLOW_PATH:
            raise ValueError(
                f"GitHub executionDefinition must be {EVIDENCE_WORKFLOW_PATH}"
            )
    elif execution_definition != "local":
        raise ValueError("local executionDefinition must be 'local'")
    require_pattern(runner_sha256, SHA256_RE, "runnerSha256")
    require_pattern(validator_sha256, SHA256_RE, "validatorSha256")
    require_pattern(policy_sha256, SHA256_RE, "policySha256")
    require_pattern(producer_policy_sha256, SHA256_RE, "producerPolicySha256")
    require_pattern(workload_sha256, SHA256_RE, "workloadSha256")
    require_pattern(client_artifact_sha256, SHA256_RE, "clientArtifactSha256")
    client, client_raw = load_json_bytes(client_path)
    observer, observer_raw = load_json_bytes(observer_path)
    validate_observation(
        client, expected_role="client-underlay", applies_to=applies_to
    )
    validate_observation(
        observer, expected_role="external-observer", applies_to=applies_to
    )
    enforce_producer_policy(
        client_collector_sha256=client["collectorSha256"],
        observer_collector_sha256=observer["collectorSha256"],
        workload_sha256=workload_sha256,
        client_artifact_sha256=client_artifact_sha256,
    )
    if client["sourceSha"] != source_sha or observer["sourceSha"] != source_sha:
        raise ValueError("observation sourceSha does not match manifest sourceSha")
    if client["correlationId"] != observer["correlationId"]:
        raise ValueError("client and observer correlationId values do not match")
    if any(
        observation["clientArtifactSha256"] != client_artifact_sha256
        for observation in (client, observer)
    ):
        raise ValueError("observation client artifact digest does not match manifest")
    if client["scenarioPlanSha256"] != observer["scenarioPlanSha256"]:
        raise ValueError("observation scenario plan digests do not match")
    if client["vantageIdSha256"] == observer["vantageIdSha256"]:
        raise ValueError("client and observer vantage identities must differ")
    if client["networkIdSha256"] == observer["networkIdSha256"]:
        raise ValueError("client and observer network identities must differ")
    latest_capture_finish = max(
        client["captureFinishedAtEpoch"], observer["captureFinishedAtEpoch"]
    )
    if generated_at_epoch < latest_capture_finish:
        raise ValueError("generatedAtEpoch predates capture completion")
    scenarios, gate_results = derive_bundle(client, observer)
    return {
        "version": MANIFEST_VERSION,
        "sourceSha": source_sha,
        "appliesTo": applies_to,
        "correlationId": client["correlationId"],
        "generatedAtEpoch": generated_at_epoch,
        "provenance": {
            "executionKind": execution_kind,
            "executionId": execution_id,
            "executionAttempt": execution_attempt,
            "executionDefinition": execution_definition,
            "runnerSha256": runner_sha256,
            "validatorSha256": validator_sha256,
            "policySha256": policy_sha256,
            "producerPolicySha256": producer_policy_sha256,
            "scenarioPlanSha256": client["scenarioPlanSha256"],
            "workloadSha256": workload_sha256,
            "clientArtifactSha256": client_artifact_sha256,
        },
        "artifacts": [
            {
                "role": "client-underlay",
                "path": ROLE_PATHS["client-underlay"],
                "sha256": sha256_bytes(client_raw),
                "rawCaptureSha256": client["rawCaptureSha256"],
                "vantageIdSha256": client["vantageIdSha256"],
                "networkIdSha256": client["networkIdSha256"],
                "collectorSha256": client["collectorSha256"],
            },
            {
                "role": "external-observer",
                "path": ROLE_PATHS["external-observer"],
                "sha256": sha256_bytes(observer_raw),
                "rawCaptureSha256": observer["rawCaptureSha256"],
                "vantageIdSha256": observer["vantageIdSha256"],
                "networkIdSha256": observer["networkIdSha256"],
                "collectorSha256": observer["collectorSha256"],
            },
        ],
        "scenarios": scenarios,
        "gateResults": gate_results,
    }


def _safe_artifact_path(artifact_root: Path, relative: Any, *, role: str) -> Path:
    expected = ROLE_PATHS[role]
    if relative != expected:
        raise ValueError(f"artifact path for {role} must be {expected}")
    root = artifact_root.resolve()
    path = artifact_root / expected
    if path.is_symlink():
        raise ValueError(f"artifact path for {role} must not be a symlink")
    if not path.is_file() or path.resolve().parent != root:
        raise ValueError(f"artifact for {role} is missing or escapes the artifact root")
    return path


def validate_manifest(
    manifest: Any,
    *,
    artifact_root: Path,
    expected_source_sha: str,
    applies_to: str,
    current_epoch: int,
    max_age_seconds: int,
    expected_execution_kind: str | None = None,
    expected_execution_id: str | None = None,
    expected_execution_attempt: int | None = None,
    require_pass: bool = False,
) -> dict[str, Any]:
    if not isinstance(manifest, dict):
        raise ValueError("manifest must be a JSON object")
    require_exact_fields(manifest, MANIFEST_FIELDS, "manifest")
    if manifest["version"] != MANIFEST_VERSION:
        raise ValueError(f"unexpected manifest version: {manifest['version']!r}")
    require_pattern(expected_source_sha, SHA1_RE, "expectedSourceSha")
    if manifest["sourceSha"] != expected_source_sha:
        raise ValueError("manifest sourceSha does not match expected sourceSha")
    if applies_to not in ALLOWED_APPLIES_TO or manifest["appliesTo"] != applies_to:
        raise ValueError("manifest appliesTo does not match requested scope")
    correlation_id = require_pattern(
        manifest["correlationId"], SHA256_RE, "manifest.correlationId"
    )
    generated = require_int(
        manifest["generatedAtEpoch"], "manifest.generatedAtEpoch", minimum=1
    )
    now = require_int(current_epoch, "currentEpoch", minimum=1)
    max_age = require_int(max_age_seconds, "maxAgeSeconds", minimum=1)
    if generated > now + MAX_CLOCK_SKEW_SECONDS:
        raise ValueError("manifest generatedAtEpoch is in the future")
    if now - generated > max_age:
        raise ValueError("manifest is stale")

    provenance = manifest["provenance"]
    if not isinstance(provenance, dict):
        raise ValueError("manifest.provenance must be an object")
    require_exact_fields(provenance, PROVENANCE_FIELDS, "manifest.provenance")
    execution_kind = provenance["executionKind"]
    if execution_kind not in ALLOWED_EXECUTION_KINDS:
        raise ValueError("manifest.provenance.executionKind is unsupported")
    execution_id = require_pattern(
        provenance["executionId"], IDENTIFIER_RE, "manifest.provenance.executionId"
    )
    execution_attempt = require_int(
        provenance["executionAttempt"],
        "manifest.provenance.executionAttempt",
        minimum=1,
    )
    execution_definition = provenance["executionDefinition"]
    if execution_kind == "github-actions":
        if execution_definition != EVIDENCE_WORKFLOW_PATH:
            raise ValueError(
                "manifest GitHub executionDefinition does not identify the evidence workflow"
            )
    elif execution_definition != "local":
        raise ValueError("manifest local executionDefinition must be 'local'")
    runner_sha256 = require_pattern(
        provenance["runnerSha256"],
        SHA256_RE,
        "manifest.provenance.runnerSha256",
    )
    if runner_sha256 != sha256_bytes(RUNNER_PATH.read_bytes()):
        raise ValueError("manifest runner digest does not match current tree")
    validator_sha256 = require_pattern(
        provenance["validatorSha256"],
        SHA256_RE,
        "manifest.provenance.validatorSha256",
    )
    if validator_sha256 != sha256_bytes(Path(__file__).read_bytes()):
        raise ValueError("manifest validator digest does not match current tree")
    policy_sha256 = require_pattern(
        provenance["policySha256"],
        SHA256_RE,
        "manifest.provenance.policySha256",
    )
    if policy_sha256 != sha256_bytes(POLICY_PATH.read_bytes()):
        raise ValueError("manifest policy digest does not match current tree")
    producer_policy_sha256 = require_pattern(
        provenance["producerPolicySha256"],
        SHA256_RE,
        "manifest.provenance.producerPolicySha256",
    )
    if producer_policy_sha256 != sha256_bytes(PRODUCER_POLICY_PATH.read_bytes()):
        raise ValueError("manifest producer policy digest does not match current tree")
    scenario_plan_sha256 = require_pattern(
        provenance["scenarioPlanSha256"],
        SHA256_RE,
        "manifest.provenance.scenarioPlanSha256",
    )
    require_pattern(
        provenance["workloadSha256"],
        SHA256_RE,
        "manifest.provenance.workloadSha256",
    )
    require_pattern(
        provenance["clientArtifactSha256"],
        SHA256_RE,
        "manifest.provenance.clientArtifactSha256",
    )
    if expected_execution_kind is not None and execution_kind != expected_execution_kind:
        raise ValueError("manifest executionKind does not match selected execution")
    if expected_execution_id is not None and execution_id != expected_execution_id:
        raise ValueError("manifest executionId does not match selected execution")
    if (
        expected_execution_attempt is not None
        and execution_attempt != expected_execution_attempt
    ):
        raise ValueError("manifest executionAttempt does not match selected execution")

    artifacts = manifest["artifacts"]
    if not isinstance(artifacts, list) or len(artifacts) != 2:
        raise ValueError("manifest must contain exactly client and observer artifacts")
    by_role: dict[str, dict[str, Any]] = {}
    observations: dict[str, dict[str, Any]] = {}
    for index, artifact in enumerate(artifacts):
        if not isinstance(artifact, dict):
            raise ValueError(f"manifest.artifacts[{index}] must be an object")
        require_exact_fields(artifact, ARTIFACT_FIELDS, f"manifest.artifacts[{index}]")
        role = artifact["role"]
        if role not in REQUIRED_ROLES or role in by_role:
            raise ValueError(
                "manifest must contain exactly client and observer artifacts"
            )
        path = _safe_artifact_path(artifact_root, artifact["path"], role=role)
        observation, raw = load_json_bytes(path)
        if raw != canonical_json_bytes(observation):
            raise ValueError(f"artifact for {role} is not canonical JSON")
        expected_digest = require_pattern(
            artifact["sha256"], SHA256_RE, f"artifact {role} digest"
        )
        if sha256_bytes(raw) != expected_digest:
            raise ValueError(f"artifact digest mismatch for {role}")
        validate_observation(
            observation, expected_role=role, applies_to=applies_to
        )
        if observation["rawCaptureSha256"] != artifact["rawCaptureSha256"]:
            raise ValueError(f"raw capture digest mismatch for {role}")
        if observation["vantageIdSha256"] != artifact["vantageIdSha256"]:
            raise ValueError(f"vantage identity digest mismatch for {role}")
        if observation["networkIdSha256"] != artifact["networkIdSha256"]:
            raise ValueError(f"network identity digest mismatch for {role}")
        if observation["collectorSha256"] != artifact["collectorSha256"]:
            raise ValueError(f"collector digest mismatch for {role}")
        if (
            observation["clientArtifactSha256"]
            != provenance["clientArtifactSha256"]
        ):
            raise ValueError(f"client artifact digest mismatch for {role}")
        if observation["scenarioPlanSha256"] != scenario_plan_sha256:
            raise ValueError(f"scenario plan digest mismatch for {role}")
        if observation["sourceSha"] != expected_source_sha:
            raise ValueError(f"observation sourceSha mismatch for {role}")
        if observation["correlationId"] != correlation_id:
            raise ValueError(f"observation correlationId mismatch for {role}")
        capture_finished = observation["captureFinishedAtEpoch"]
        if capture_finished > now + MAX_CLOCK_SKEW_SECONDS:
            raise ValueError(f"observation capture is in the future for {role}")
        if now - capture_finished > max_age:
            raise ValueError(f"observation capture is stale for {role}")
        by_role[role] = artifact
        observations[role] = observation
    if set(by_role) != set(REQUIRED_ROLES):
        raise ValueError("manifest must contain exactly client and observer artifacts")
    enforce_producer_policy(
        client_collector_sha256=by_role["client-underlay"]["collectorSha256"],
        observer_collector_sha256=by_role["external-observer"]["collectorSha256"],
        workload_sha256=provenance["workloadSha256"],
        client_artifact_sha256=provenance["clientArtifactSha256"],
    )
    if (
        observations["client-underlay"]["vantageIdSha256"]
        == observations["external-observer"]["vantageIdSha256"]
    ):
        raise ValueError("client and observer vantage identities must differ")
    if (
        observations["client-underlay"]["networkIdSha256"]
        == observations["external-observer"]["networkIdSha256"]
    ):
        raise ValueError("client and observer network identities must differ")

    scenarios, gate_results = derive_bundle(
        observations["client-underlay"], observations["external-observer"]
    )
    if manifest["scenarios"] != scenarios:
        raise ValueError("manifest scenarios do not match derived observation windows")
    if manifest["gateResults"] != gate_results:
        raise ValueError(
            "manifest derived gateResults do not match observation counters"
        )
    if require_pass:
        non_pass = {
            gate_id: result
            for gate_id, result in gate_results.items()
            if result != "PASS"
        }
        if non_pass:
            raise ValueError(f"evidence bundle is not all PASS: {non_pass}")
    if generated < max(
        observation["captureFinishedAtEpoch"] for observation in observations.values()
    ):
        raise ValueError("manifest generatedAtEpoch predates capture completion")
    return {"gateResults": gate_results, "scenarioCount": len(scenarios)}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    assemble = subparsers.add_parser(
        "assemble", help="Assemble a manifest from two observations"
    )
    assemble.add_argument("--client", type=Path, required=True)
    assemble.add_argument("--observer", type=Path, required=True)
    assemble.add_argument("--source-sha", required=True)
    assemble.add_argument(
        "--applies-to", choices=sorted(ALLOWED_APPLIES_TO), required=True
    )
    assemble.add_argument("--generated-at-epoch", type=int, default=None)
    assemble.add_argument(
        "--execution-kind", choices=sorted(ALLOWED_EXECUTION_KINDS), required=True
    )
    assemble.add_argument("--execution-id", required=True)
    assemble.add_argument("--execution-attempt", type=int, required=True)
    assemble.add_argument("--execution-definition", required=True)
    assemble.add_argument("--runner-sha256", required=True)
    assemble.add_argument("--validator-sha256", required=True)
    assemble.add_argument("--policy-sha256", required=True)
    assemble.add_argument("--producer-policy-sha256", required=True)
    assemble.add_argument("--workload-sha256", required=True)
    assemble.add_argument("--client-artifact-sha256", required=True)
    assemble.add_argument("--output", type=Path, required=True)

    stamp = subparsers.add_parser(
        "stamp-observation",
        help="Bind a hook summary to runner-owned vantage and collector digests",
    )
    stamp.add_argument("--input", type=Path, required=True)
    stamp.add_argument("--plan", type=Path, required=True)
    stamp.add_argument("--output", type=Path, required=True)
    stamp.add_argument("--role", choices=REQUIRED_ROLES, required=True)
    stamp.add_argument("--source-sha", required=True)
    stamp.add_argument("--correlation-id", required=True)
    stamp.add_argument("--vantage-id-sha256", required=True)
    stamp.add_argument("--network-id-sha256", required=True)
    stamp.add_argument("--collector-sha256", required=True)
    stamp.add_argument("--client-artifact-sha256", required=True)
    stamp.add_argument(
        "--applies-to",
        choices=sorted(ALLOWED_APPLIES_TO),
        default="android-client-release",
    )

    validate = subparsers.add_parser(
        "validate", help="Validate a complete evidence bundle"
    )
    validate.add_argument("--manifest", type=Path, required=True)
    validate.add_argument("--artifact-root", type=Path, required=True)
    validate.add_argument("--expected-source-sha", required=True)
    validate.add_argument(
        "--applies-to", choices=sorted(ALLOWED_APPLIES_TO), required=True
    )
    validate.add_argument("--current-epoch", type=int, default=None)
    validate.add_argument("--max-age-seconds", type=int, default=604800)
    validate.add_argument(
        "--expected-execution-kind", choices=sorted(ALLOWED_EXECUTION_KINDS)
    )
    validate.add_argument("--expected-execution-id")
    validate.add_argument("--expected-execution-attempt", type=int)
    validate.add_argument("--require-pass", action="store_true")
    validate.add_argument("--results-output", type=Path)

    validate_plan_parser = subparsers.add_parser(
        "validate-plan", help="Validate and canonicalize a workload scenario plan"
    )
    validate_plan_parser.add_argument("--input", type=Path, required=True)
    validate_plan_parser.add_argument("--output", type=Path, required=True)
    validate_plan_parser.add_argument("--source-sha", required=True)
    validate_plan_parser.add_argument("--correlation-id", required=True)
    validate_plan_parser.add_argument("--client-artifact-sha256", required=True)
    validate_plan_parser.add_argument(
        "--applies-to", choices=sorted(ALLOWED_APPLIES_TO), required=True
    )

    args = parser.parse_args(argv)
    try:
        if args.command == "assemble":
            prepare_cli_output(
                args.output,
                inputs=(args.client, args.observer, POLICY_PATH, PRODUCER_POLICY_PATH),
            )
            manifest = assemble_manifest(
                client_path=args.client,
                observer_path=args.observer,
                source_sha=args.source_sha,
                applies_to=args.applies_to,
                generated_at_epoch=args.generated_at_epoch or int(time.time()),
                execution_kind=args.execution_kind,
                execution_id=args.execution_id,
                execution_attempt=args.execution_attempt,
                execution_definition=args.execution_definition,
                runner_sha256=args.runner_sha256,
                validator_sha256=args.validator_sha256,
                policy_sha256=args.policy_sha256,
                producer_policy_sha256=args.producer_policy_sha256,
                workload_sha256=args.workload_sha256,
                client_artifact_sha256=args.client_artifact_sha256,
            )
            write_canonical_json(args.output, manifest)
            return 0
        if args.command == "stamp-observation":
            prepare_cli_output(
                args.output,
                inputs=(args.input, args.plan, POLICY_PATH, PRODUCER_POLICY_PATH),
            )
            observation, _ = load_json_bytes(args.input)
            plan, _ = load_json_bytes(args.plan)
            validated_plan = validate_plan(
                plan,
                expected_source_sha=args.source_sha,
                expected_correlation_id=args.correlation_id,
                expected_client_artifact_sha256=args.client_artifact_sha256,
                applies_to=args.applies_to,
            )
            stamped = stamp_observation(
                observation,
                plan=validated_plan,
                expected_role=args.role,
                expected_source_sha=args.source_sha,
                expected_correlation_id=args.correlation_id,
                vantage_id_sha256=args.vantage_id_sha256,
                network_id_sha256=args.network_id_sha256,
                collector_sha256=args.collector_sha256,
                client_artifact_sha256=args.client_artifact_sha256,
                applies_to=args.applies_to,
            )
            write_canonical_json(args.output, stamped)
            return 0
        if args.command == "validate-plan":
            prepare_cli_output(
                args.output, inputs=(args.input, POLICY_PATH, PRODUCER_POLICY_PATH)
            )
            plan, _ = load_json_bytes(args.input)
            validated_plan = validate_plan(
                plan,
                expected_source_sha=args.source_sha,
                expected_correlation_id=args.correlation_id,
                expected_client_artifact_sha256=args.client_artifact_sha256,
                applies_to=args.applies_to,
            )
            write_canonical_json(args.output, validated_plan)
            return 0
        prepare_cli_output(
            args.results_output,
            inputs=(
                args.manifest,
                *(args.artifact_root / path for path in ROLE_PATHS.values()),
                POLICY_PATH,
                PRODUCER_POLICY_PATH,
            ),
        )
        manifest, _ = load_json_bytes(args.manifest)
        summary = validate_manifest(
            manifest,
            artifact_root=args.artifact_root,
            expected_source_sha=args.expected_source_sha,
            applies_to=args.applies_to,
            current_epoch=args.current_epoch or int(time.time()),
            max_age_seconds=args.max_age_seconds,
            expected_execution_kind=args.expected_execution_kind,
            expected_execution_id=args.expected_execution_id,
            expected_execution_attempt=args.expected_execution_attempt,
            require_pass=args.require_pass,
        )
        if args.results_output:
            write_canonical_json(
                args.results_output,
                {
                    "version": "dns_ipv6_killswitch_results_v1",
                    "sourceSha": args.expected_source_sha,
                    "appliesTo": args.applies_to,
                    "gateResults": summary["gateResults"],
                },
            )
        print(
            f"Validated {summary['scenarioCount']} dual-vantage network evidence scenarios."
        )
        return 0
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        print(f"network evidence validation failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
