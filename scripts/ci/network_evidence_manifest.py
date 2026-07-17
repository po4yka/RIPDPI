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

MANIFEST_VERSION = "network_evidence_manifest_v1"
OBSERVATION_VERSION = "network_evidence_observation_v1"
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

OBSERVATION_FIELDS = {
    "version",
    "sourceSha",
    "correlationId",
    "role",
    "vantageIdSha256",
    "collectorSha256",
    "captureStartedAtEpoch",
    "captureFinishedAtEpoch",
    "rawCaptureSha256",
    "windows",
}
UNSTAMPED_OBSERVATION_FIELDS = OBSERVATION_FIELDS - {
    "vantageIdSha256",
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
    "workflowPath",
    "workflowRunId",
    "workflowRunAttempt",
    "workloadSha256",
    "clientArtifactSha256",
}
ARTIFACT_FIELDS = {
    "role",
    "path",
    "sha256",
    "rawCaptureSha256",
    "vantageIdSha256",
    "collectorSha256",
}
SCENARIO_FIELDS = {"id", "kind", "startedAtEpoch", "finishedAtEpoch"}


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


def load_json_bytes(path: Path) -> tuple[dict[str, Any], bytes]:
    raw = path.read_bytes()
    value = json.loads(raw.decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path.name} must contain a JSON object")
    return value, raw


def required_gate_ids() -> set[str]:
    policy = json.loads(POLICY_PATH.read_text(encoding="utf-8"))
    return {gate["id"] for gate in policy["gates"]}


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


def validate_window(
    window: Any, *, context: str, capture_start: int, capture_finish: int
) -> dict[str, Any]:
    if not isinstance(window, dict):
        raise ValueError(f"{context} must be an object")
    require_exact_fields(window, WINDOW_FIELDS, context)
    gate_id = require_pattern(window["id"], IDENTIFIER_RE, f"{context}.id")
    if gate_id not in required_gate_ids():
        raise ValueError(f"{context}.id is not a required release gate")
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
    return window


def validate_observation(
    value: Any, *, expected_role: str | None = None
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
    require_pattern(value["collectorSha256"], SHA256_RE, "observation.collectorSha256")
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
    for index, window in enumerate(windows):
        validated = validate_window(
            window,
            context=f"observation.windows[{index}]",
            capture_start=capture_start,
            capture_finish=capture_finish,
        )
        gate_id = validated["id"]
        if gate_id in seen:
            raise ValueError(f"observation has duplicate window id: {gate_id}")
        seen.add(gate_id)
    expected_ids = required_gate_ids()
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
    expected_role: str,
    expected_source_sha: str,
    expected_correlation_id: str,
    vantage_id_sha256: str,
    collector_sha256: str,
) -> dict[str, Any]:
    """Bind a hook summary to runner-owned collector and vantage identities."""
    if not isinstance(value, dict):
        raise ValueError("unstamped observation must be a JSON object")
    require_exact_fields(value, UNSTAMPED_OBSERVATION_FIELDS, "unstamped observation")
    require_pattern(expected_source_sha, SHA1_RE, "expectedSourceSha")
    require_pattern(expected_correlation_id, SHA256_RE, "expectedCorrelationId")
    require_pattern(vantage_id_sha256, SHA256_RE, "vantageIdSha256")
    require_pattern(collector_sha256, SHA256_RE, "collectorSha256")
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
    stamped = dict(value)
    stamped["vantageIdSha256"] = vantage_id_sha256
    stamped["collectorSha256"] = collector_sha256
    return validate_observation(stamped, expected_role=expected_role)


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
    workflow_path: str,
    workflow_run_id: int,
    workflow_run_attempt: int,
    workload_sha256: str,
    client_artifact_sha256: str,
) -> dict[str, Any]:
    require_pattern(source_sha, SHA1_RE, "sourceSha")
    if applies_to not in ALLOWED_APPLIES_TO:
        raise ValueError(f"unsupported appliesTo: {applies_to!r}")
    require_int(generated_at_epoch, "generatedAtEpoch", minimum=1)
    if workflow_path != EVIDENCE_WORKFLOW_PATH:
        raise ValueError(f"workflowPath must be {EVIDENCE_WORKFLOW_PATH}")
    require_int(workflow_run_id, "workflowRunId", minimum=1)
    require_int(workflow_run_attempt, "workflowRunAttempt", minimum=1)
    require_pattern(workload_sha256, SHA256_RE, "workloadSha256")
    require_pattern(client_artifact_sha256, SHA256_RE, "clientArtifactSha256")
    client, client_raw = load_json_bytes(client_path)
    observer, observer_raw = load_json_bytes(observer_path)
    validate_observation(client, expected_role="client-underlay")
    validate_observation(observer, expected_role="external-observer")
    if client["sourceSha"] != source_sha or observer["sourceSha"] != source_sha:
        raise ValueError("observation sourceSha does not match manifest sourceSha")
    if client["correlationId"] != observer["correlationId"]:
        raise ValueError("client and observer correlationId values do not match")
    if client["vantageIdSha256"] == observer["vantageIdSha256"]:
        raise ValueError("client and observer vantage identities must differ")
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
            "workflowPath": workflow_path,
            "workflowRunId": workflow_run_id,
            "workflowRunAttempt": workflow_run_attempt,
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
                "collectorSha256": client["collectorSha256"],
            },
            {
                "role": "external-observer",
                "path": ROLE_PATHS["external-observer"],
                "sha256": sha256_bytes(observer_raw),
                "rawCaptureSha256": observer["rawCaptureSha256"],
                "vantageIdSha256": observer["vantageIdSha256"],
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
    expected_workflow_run_id: int | None = None,
    expected_workflow_run_attempt: int | None = None,
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
    if provenance["workflowPath"] != EVIDENCE_WORKFLOW_PATH:
        raise ValueError(
            "manifest workflowPath does not identify the evidence workflow"
        )
    run_id = require_int(
        provenance["workflowRunId"], "manifest.provenance.workflowRunId", minimum=1
    )
    run_attempt = require_int(
        provenance["workflowRunAttempt"],
        "manifest.provenance.workflowRunAttempt",
        minimum=1,
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
    if expected_workflow_run_id is not None and run_id != expected_workflow_run_id:
        raise ValueError(
            "manifest workflowRunId does not match the selected evidence run"
        )
    if (
        expected_workflow_run_attempt is not None
        and run_attempt != expected_workflow_run_attempt
    ):
        raise ValueError(
            "manifest workflowRunAttempt does not match the selected evidence run"
        )

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
        validate_observation(observation, expected_role=role)
        if observation["rawCaptureSha256"] != artifact["rawCaptureSha256"]:
            raise ValueError(f"raw capture digest mismatch for {role}")
        if observation["vantageIdSha256"] != artifact["vantageIdSha256"]:
            raise ValueError(f"vantage identity digest mismatch for {role}")
        if observation["collectorSha256"] != artifact["collectorSha256"]:
            raise ValueError(f"collector digest mismatch for {role}")
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
    if (
        observations["client-underlay"]["vantageIdSha256"]
        == observations["external-observer"]["vantageIdSha256"]
    ):
        raise ValueError("client and observer vantage identities must differ")

    scenarios, gate_results = derive_bundle(
        observations["client-underlay"], observations["external-observer"]
    )
    if manifest["scenarios"] != scenarios:
        raise ValueError("manifest scenarios do not match derived observation windows")
    if manifest["gateResults"] != gate_results:
        raise ValueError(
            "manifest derived gateResults do not match observation counters"
        )
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
    assemble.add_argument("--workflow-path", default=EVIDENCE_WORKFLOW_PATH)
    assemble.add_argument("--workflow-run-id", type=int, required=True)
    assemble.add_argument("--workflow-run-attempt", type=int, required=True)
    assemble.add_argument("--workload-sha256", required=True)
    assemble.add_argument("--client-artifact-sha256", required=True)
    assemble.add_argument("--output", type=Path, required=True)

    stamp = subparsers.add_parser(
        "stamp-observation",
        help="Bind a hook summary to runner-owned vantage and collector digests",
    )
    stamp.add_argument("--input", type=Path, required=True)
    stamp.add_argument("--output", type=Path, required=True)
    stamp.add_argument("--role", choices=REQUIRED_ROLES, required=True)
    stamp.add_argument("--source-sha", required=True)
    stamp.add_argument("--correlation-id", required=True)
    stamp.add_argument("--vantage-id-sha256", required=True)
    stamp.add_argument("--collector-sha256", required=True)

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
    validate.add_argument("--expected-workflow-run-id", type=int)
    validate.add_argument("--expected-workflow-run-attempt", type=int)
    validate.add_argument("--results-output", type=Path)

    args = parser.parse_args(argv)
    try:
        if args.command == "assemble":
            prepare_cli_output(
                args.output,
                inputs=(args.client, args.observer, POLICY_PATH),
            )
            manifest = assemble_manifest(
                client_path=args.client,
                observer_path=args.observer,
                source_sha=args.source_sha,
                applies_to=args.applies_to,
                generated_at_epoch=args.generated_at_epoch or int(time.time()),
                workflow_path=args.workflow_path,
                workflow_run_id=args.workflow_run_id,
                workflow_run_attempt=args.workflow_run_attempt,
                workload_sha256=args.workload_sha256,
                client_artifact_sha256=args.client_artifact_sha256,
            )
            write_canonical_json(args.output, manifest)
            return 0
        if args.command == "stamp-observation":
            prepare_cli_output(args.output, inputs=(args.input, POLICY_PATH))
            observation, _ = load_json_bytes(args.input)
            stamped = stamp_observation(
                observation,
                expected_role=args.role,
                expected_source_sha=args.source_sha,
                expected_correlation_id=args.correlation_id,
                vantage_id_sha256=args.vantage_id_sha256,
                collector_sha256=args.collector_sha256,
            )
            write_canonical_json(args.output, stamped)
            return 0
        prepare_cli_output(
            args.results_output,
            inputs=(
                args.manifest,
                *(args.artifact_root / path for path in ROLE_PATHS.values()),
                POLICY_PATH,
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
            expected_workflow_run_id=args.expected_workflow_run_id,
            expected_workflow_run_attempt=args.expected_workflow_run_attempt,
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
