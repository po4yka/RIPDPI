from __future__ import annotations

import tempfile
import zipfile
from collections import Counter, defaultdict
from collections.abc import Iterable, Iterator
from pathlib import Path
from typing import Any
import json

from .common import (
    EMPTY_VALUE,
    FEATURE_SCHEMA_VERSION,
    OFFLINE_RECORD_SCHEMA_VERSION,
    RUNTIME_COMPONENT_KEYS,
    classify_target_category,
    dominant_values,
    is_failure_outcome,
    is_success_outcome,
    load_csv_rows,
    load_json_optional,
    normalize_error_kind,
    normalize_target_label,
    normalize_winner_family,
    now_iso_utc,
    parse_probe_detail_json,
    safe_relative_path,
    slugify,
    split_comma_values,
    split_pipe_values,
    split_semicolon_values,
    stable_hash,
)


def run_extract(inputs: Iterable[Path]) -> dict[str, Any]:
    records = [extract_record(root, source_path) for root, source_path in iter_archive_roots(inputs)]
    return {
        "schemaVersion": OFFLINE_RECORD_SCHEMA_VERSION,
        "featureSchemaVersion": FEATURE_SCHEMA_VERSION,
        "generatedAt": now_iso_utc(),
        "recordCount": len(records),
        "records": records,
    }


def iter_archive_roots(inputs: Iterable[Path]) -> Iterator[tuple[Path, str]]:
    for raw_input in inputs:
        path = raw_input.resolve()
        if path.is_dir():
            yield path, safe_relative_path(path)
            continue
        if path.is_file() and path.suffix.lower() == ".zip":
            with tempfile.TemporaryDirectory(prefix="ripdpi-analytics-") as temp_dir:
                temp_path = Path(temp_dir)
                with zipfile.ZipFile(path) as archive:
                    archive.extractall(temp_path)
                yield temp_path, safe_relative_path(path)
            continue
        raise FileNotFoundError(f"Unsupported analytics input: {path}")


def extract_record(root: Path, source_path: str) -> dict[str, Any]:
    manifest = load_json_optional(root / "manifest.json") or {}
    home_analysis = load_json_optional(root / "home-analysis.json") or {}
    analysis = load_json_optional(root / "analysis.json") or {}
    runtime_config = load_json_optional(root / "runtime-config.json") or {}
    diagnostic_context = load_json_optional(root / "diagnostic-context.json") or {}
    developer_analytics = load_json_optional(root / "developer-analytics.json") or {}
    telemetry_rows = collect_csv_rows(root, "telemetry.csv")
    probe_rows = collect_probe_rows(root)

    network_scope_key = str(
        home_analysis.get("fingerprintHash")
        or first_non_blank(row.get("telemetryNetworkFingerprintHash") for row in telemetry_rows)
        or manifest.get("homeRunId")
        or stable_hash(source_path)[:32]
    )
    runtime_summary = extract_runtime_summary(runtime_config, diagnostic_context)
    winner_summary = extract_winner_summary(runtime_config, telemetry_rows)
    control_outcome = extract_control_outcome(probe_rows)
    resolver_assessment = extract_resolver_assessment(probe_rows)
    http_assessment = extract_http_assessment(probe_rows)
    tls_assessment = extract_tls_assessment(probe_rows)
    route_assessment = extract_route_assessment(probe_rows)
    trigger_fuzz_assessment = extract_trigger_fuzz_assessment(probe_rows)
    affected_targets = extract_affected_targets(probe_rows)
    connectivity_assessment = derive_connectivity_assessment(
        control_outcome=control_outcome,
        resolver_assessment=resolver_assessment,
        http_assessment=http_assessment,
        tls_assessment=tls_assessment,
        route_assessment=route_assessment,
        runtime_summary=runtime_summary,
        affected_targets=affected_targets,
        home_analysis=home_analysis,
    )
    observed_features = sorted(
        build_observed_features(
            connectivity_assessment=connectivity_assessment,
            runtime_summary=runtime_summary,
            resolver_assessment=resolver_assessment,
            http_assessment=http_assessment,
            tls_assessment=tls_assessment,
            route_assessment=route_assessment,
            trigger_fuzz_assessment=trigger_fuzz_assessment,
            winner_summary=winner_summary,
        )
    )
    return {
        "schemaVersion": OFFLINE_RECORD_SCHEMA_VERSION,
        "featureSchemaVersion": FEATURE_SCHEMA_VERSION,
        "recordId": str(manifest.get("homeRunId") or manifest.get("fileName") or Path(source_path).stem),
        "sourcePath": source_path,
        "runType": manifest.get("runType") or "single_session",
        "generatedAt": manifest.get("createdAt"),
        "networkScopeKey": network_scope_key,
        "connectivityAssessment": connectivity_assessment,
        "controlOutcome": control_outcome,
        "affectedTargets": affected_targets,
        "resolverAssessment": resolver_assessment,
        "httpAssessment": http_assessment,
        "tlsAssessment": tls_assessment,
        "routeAssessment": route_assessment,
        "triggerFuzzAssessment": trigger_fuzz_assessment,
        "runtimeSummary": runtime_summary,
        "winnerSummary": winner_summary,
        "archiveSummary": {
            "headline": home_analysis.get("headline") or analysis.get("summary"),
            "summary": home_analysis.get("summary"),
            "bundleSessionCount": len(home_analysis.get("bundleSessionIds", [])),
            "sampleCount": len(telemetry_rows),
            "probeCount": len(probe_rows),
            "networkSnapshots": len(developer_analytics.get("networkSnapshots", [])),
        },
        "observedFeatures": observed_features,
    }


def collect_csv_rows(root: Path, file_name: str) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    direct_path = root / file_name
    if direct_path.exists():
        rows.extend(load_csv_rows(direct_path))
    for stage_file in sorted((root / "stages").rglob(file_name)) if (root / "stages").exists() else []:
        rows.extend(load_csv_rows(stage_file))
    return rows


def collect_probe_rows(root: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    candidate_paths = [root / "probe-results.csv"]
    if (root / "stages").exists():
        candidate_paths.extend(sorted((root / "stages").rglob("probe-results.csv")))
    for path in candidate_paths:
        if not path.exists():
            continue
        stage_key = stage_key_for(path, root)
        for row in load_csv_rows(path):
            details = parse_probe_detail_json(row.get("detailJson"))
            target = row.get("target", "")
            rows.append(
                {
                    "stageKey": stage_key,
                    "probeType": row.get("probeType", ""),
                    "target": target,
                    "targetLabel": normalize_target_label(target),
                    "targetCategory": classify_target_category(row.get("probeType", ""), target),
                    "outcome": row.get("outcome", ""),
                    "details": details,
                }
            )
    return rows


def stage_key_for(path: Path, root: Path) -> str:
    relative = path.resolve().relative_to(root.resolve()).parts
    if len(relative) >= 3 and relative[0] == "stages":
        return relative[1]
    return "primary"


def extract_runtime_summary(
    runtime_config: dict[str, Any],
    diagnostic_context: dict[str, Any],
) -> dict[str, Any]:
    service_status = str(runtime_config.get("serviceStatus") or "unknown")
    last_native_error = normalize_runtime_error(runtime_config.get("lastNativeErrorHeadline"))
    session_contexts = diagnostic_context.get("sessionContexts", [])
    components = extract_runtime_components(runtime_config, session_contexts)
    return {
        "serviceStatus": service_status,
        "activeMode": runtime_config.get("activeMode"),
        "configuredMode": runtime_config.get("configuredMode"),
        "lastNativeErrorHeadline": last_native_error,
        "components": components,
    }


def extract_runtime_components(
    runtime_config: dict[str, Any],
    session_contexts: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    for key in RUNTIME_COMPONENT_KEYS:
        component = runtime_config.get(key)
        if not isinstance(component, dict):
            component = first_component_from_context(session_contexts, key)
        if isinstance(component, dict):
            normalized.append(
                {
                    "name": key,
                    "state": component.get("state") or component.get("status") or EMPTY_VALUE,
                    "health": component.get("health") or EMPTY_VALUE,
                    "activeSessions": component.get("activeSessions") or 0,
                    "lastError": normalize_runtime_error(component.get("lastError")),
                    "lastFailureClass": component.get("lastFailureClass") or EMPTY_VALUE,
                    "listenerAddress": normalize_endpoint(component.get("listenerAddress")),
                    "upstreamAddress": normalize_endpoint(component.get("upstreamAddress")),
                    "capturedAt": component.get("capturedAt") or component.get("timestampMs"),
                }
            )
    if normalized:
        return normalized
    return [
        {
            "name": "service",
            "state": runtime_config.get("serviceStatus") or EMPTY_VALUE,
            "health": "error" if normalize_runtime_error(runtime_config.get("lastNativeErrorHeadline")) else "unknown",
            "activeSessions": 0,
            "lastError": normalize_runtime_error(runtime_config.get("lastNativeErrorHeadline")),
            "lastFailureClass": EMPTY_VALUE,
            "listenerAddress": EMPTY_VALUE,
            "upstreamAddress": EMPTY_VALUE,
            "capturedAt": runtime_config.get("capturedAt"),
        }
    ]


def first_component_from_context(session_contexts: list[dict[str, Any]], key: str) -> dict[str, Any] | None:
    for context in session_contexts:
        components = context.get("runtimeComponents") or context.get("serviceRuntime")
        if isinstance(components, dict) and isinstance(components.get(key), dict):
            return components[key]
    return None


def normalize_runtime_error(value: Any) -> str | None:
    if value is None:
        return None
    normalized = str(value).strip()
    if not normalized or normalized.lower() == EMPTY_VALUE:
        return None
    return normalized


def normalize_endpoint(value: Any) -> str:
    if value is None:
        return EMPTY_VALUE
    candidate = str(value).strip()
    if not candidate:
        return EMPTY_VALUE
    if "://" in candidate:
        return "redacted_url"
    if ":" in candidate:
        host = candidate.split(":", 1)[0]
        return f"{host}:redacted" if host else "redacted_endpoint"
    return candidate


def extract_winner_summary(runtime_config: dict[str, Any], telemetry_rows: list[dict[str, str]]) -> dict[str, Any]:
    latest_row = telemetry_rows[-1] if telemetry_rows else {}
    family = normalize_winner_family(latest_row.get("winningStrategyFamily"))
    tcp_family = normalize_winner_family(latest_row.get("winningTcpStrategyFamily"))
    quic_family = normalize_winner_family(latest_row.get("winningQuicStrategyFamily"))
    signature = runtime_config.get("effectiveStrategySignature")
    signature_hash = stable_hash(json_like(signature))[:16] if signature else None
    return {
        "family": family,
        "tcpFamily": tcp_family,
        "quicFamily": quic_family,
        "signatureHash": signature_hash,
        "signature": signature,
    }


def json_like(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, dict):
        return "".join(f"{key}:{json_like(value[key])};" for key in sorted(value))
    if isinstance(value, list):
        return "[" + ",".join(json_like(item) for item in value) + "]"
    return str(value)


def extract_control_outcome(probe_rows: list[dict[str, Any]]) -> dict[str, Any]:
    control_rows = [row for row in probe_rows if row["details"].get("isControl") == "true"]
    blocked_rows = [
        row
        for row in probe_rows
        if row["targetCategory"] in {"domain", "quic", "tcp_path"}
        and row["details"].get("isControl") != "true"
    ]
    return {
        "controlSuccessCount": sum(1 for row in control_rows if is_success_outcome(row["outcome"])),
        "controlFailureCount": sum(1 for row in control_rows if is_failure_outcome(row["outcome"])),
        "blockedTargetSuccessCount": sum(1 for row in blocked_rows if is_success_outcome(row["outcome"])),
        "blockedTargetFailureCount": sum(1 for row in blocked_rows if is_failure_outcome(row["outcome"])),
    }


def extract_resolver_assessment(probe_rows: list[dict[str, Any]]) -> dict[str, Any]:
    outcomes = Counter()
    comparison_signals = Counter()
    disagreement_resolvers = Counter()
    affected_targets: list[str] = []
    injection_suspected = False
    for row in probe_rows:
        if row["probeType"] != "dns_integrity":
            continue
        outcome = row["outcome"]
        if outcome != "dns_match":
            outcomes[outcome] += 1
            affected_targets.append(row["targetLabel"])
        details = row["details"]
        for signal in split_pipe_values(details.get("comparisonSignals")):
            comparison_signals[signal] += 1
        if details.get("oracleTrust") == "disagreement":
            disagreement_resolvers.update(split_pipe_values(details.get("oracleDisagreementResolvers")))
        if details.get("dnsInjectionSuspected") == "true":
            injection_suspected = True
    return {
        "signalCount": sum(outcomes.values()) + sum(comparison_signals.values()),
        "outcomes": counter_to_sorted_items(outcomes),
        "comparisonSignals": counter_to_sorted_items(comparison_signals),
        "disagreementResolvers": counter_to_sorted_items(disagreement_resolvers),
        "injectionSuspected": injection_suspected,
        "affectedTargets": sorted(set(affected_targets)),
    }


def extract_http_assessment(probe_rows: list[dict[str, Any]]) -> dict[str, Any]:
    statuses = Counter()
    redirects = Counter()
    responses = Counter()
    for row in probe_rows:
        details = row["details"]
        status = details.get("httpStatus") or details.get("status")
        if status and status != "http_ok":
            statuses[status] += 1
        response = details.get("httpResponse")
        if response and response != EMPTY_VALUE:
            responses[normalize_http_response(response)] += 1
        redirect = details.get("redirectLocation")
        if redirect:
            redirects[normalize_redirect_location(redirect)] += 1
    return {
        "statuses": counter_to_sorted_items(statuses),
        "responses": counter_to_sorted_items(responses),
        "redirects": counter_to_sorted_items(redirects),
        "redirectCount": sum(redirects.values()),
    }


def extract_tls_assessment(probe_rows: list[dict[str, Any]]) -> dict[str, Any]:
    outcomes = Counter()
    signals = Counter()
    errors = Counter()
    ja3 = Counter()
    for row in probe_rows:
        if row["probeType"] not in {"domain_reachability", "strategy_https"}:
            continue
        if "tls" in row["outcome"] and row["outcome"] != "tls_ok":
            outcomes[row["outcome"]] += 1
        details = row["details"]
        signal = details.get("tlsSignal")
        if signal and signal != EMPTY_VALUE:
            signals[signal] += 1
        error_kind = normalize_error_kind(details.get("tlsError"))
        if error_kind:
            errors[error_kind] += 1
        if details.get("ja3Fingerprint"):
            ja3[details["ja3Fingerprint"]] += 1
        for key in ("tls13Status", "tls12Status", "tlsEchStatus"):
            status = details.get(key)
            if status and status != "tls_ok" and status != "not_run":
                outcomes[status] += 1
    return {
        "outcomes": counter_to_sorted_items(outcomes),
        "signals": counter_to_sorted_items(signals),
        "errors": counter_to_sorted_items(errors),
        "ja3Fingerprints": counter_to_sorted_items(ja3),
    }


def extract_route_assessment(probe_rows: list[dict[str, Any]]) -> dict[str, Any]:
    selected_bucket_kinds = Counter()
    summary_hints = Counter()
    local_addresses = Counter()
    for row in probe_rows:
        details = row["details"]
        for key, value in details.items():
            if key.endswith("RouteSelectedBucketKind") and value:
                selected_bucket_kinds[value] += 1
            if key.endswith("RouteSummary") and value:
                summary_hints[normalize_route_summary(value)] += 1
            if key.endswith("LocalAddress") and value:
                local_addresses[normalize_local_address(value)] += 1
    route_sensitive = "diversity" in selected_bucket_kinds or any(
        "diversity" in hint or "bucket#" in hint for hint in summary_hints
    )
    return {
        "routeSensitive": route_sensitive,
        "selectedBucketKinds": counter_to_sorted_items(selected_bucket_kinds),
        "summaryHints": counter_to_sorted_items(summary_hints),
        "localAddressBuckets": counter_to_sorted_items(local_addresses),
    }


def extract_trigger_fuzz_assessment(probe_rows: list[dict[str, Any]]) -> dict[str, Any]:
    changed_fields_by_family: dict[str, Counter[str]] = defaultdict(Counter)
    for row in probe_rows:
        details = row["details"]
        for family_key in ("httpFuzzChangedFields", "tlsFuzzChangedFields", "dnsFuzzChangedFields"):
            family = family_key.removesuffix("ChangedFields")
            changed = details.get(family_key)
            if not changed or changed == "none":
                continue
            for field in split_pipe_values(changed):
                changed_fields_by_family[family][field] += 1
    return {
        "changedFields": {
            family: counter_to_sorted_items(counter)
            for family, counter in sorted(changed_fields_by_family.items())
        }
    }


def extract_affected_targets(probe_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_label: dict[str, dict[str, Any]] = {}
    for row in probe_rows:
        if not is_failure_outcome(row["outcome"]):
            continue
        label = row["targetLabel"]
        entry = by_label.setdefault(
            label,
            {
                "label": label,
                "category": row["targetCategory"],
                "outcomes": Counter(),
                "stages": Counter(),
            },
        )
        entry["outcomes"][row["outcome"]] += 1
        entry["stages"][row["stageKey"]] += 1
    result = []
    for label, entry in sorted(by_label.items()):
        result.append(
            {
                "label": label,
                "category": entry["category"],
                "outcomes": counter_to_sorted_items(entry["outcomes"]),
                "stages": counter_to_sorted_items(entry["stages"]),
            }
        )
    return result


def derive_connectivity_assessment(
    control_outcome: dict[str, Any],
    resolver_assessment: dict[str, Any],
    http_assessment: dict[str, Any],
    tls_assessment: dict[str, Any],
    route_assessment: dict[str, Any],
    runtime_summary: dict[str, Any],
    affected_targets: list[dict[str, Any]],
    home_analysis: dict[str, Any],
) -> dict[str, Any]:
    control_success = int(control_outcome["controlSuccessCount"])
    control_failure = int(control_outcome["controlFailureCount"])
    blocked_failure = int(control_outcome["blockedTargetFailureCount"])
    runtime_error = runtime_summary.get("lastNativeErrorHeadline")
    runtime_problem = runtime_summary.get("serviceStatus") in {"Failed", "Halted"} and runtime_error
    resolver_signal_count = int(resolver_assessment["signalCount"])
    tls_failure_count = sum(item["count"] for item in tls_assessment["outcomes"])
    redirect_count = int(http_assessment["redirectCount"])

    if resolver_signal_count > 0 and resolver_signal_count >= max(tls_failure_count, redirect_count):
        code = "resolver_interference"
        confidence = min(0.98, 0.55 + resolver_signal_count * 0.08)
    elif runtime_problem and blocked_failure <= 1:
        code = "service_runtime_failure"
        confidence = 0.78
    elif control_failure > 0 and control_success == 0 and blocked_failure == 0:
        code = "raw_network_general_failure"
        confidence = 0.82
    elif control_success > 0 and blocked_failure > 0:
        code = "raw_network_selective_blocking"
        confidence = min(0.96, 0.52 + blocked_failure * 0.05)
    else:
        code = "mixed_or_inconclusive"
        confidence = 0.5

    summary = str(home_analysis.get("summary") or home_analysis.get("headline") or code.replace("_", " "))
    if route_assessment["routeSensitive"] and code == "mixed_or_inconclusive":
        summary = f"{summary} Route-sensitive behavior observed."
    if affected_targets:
        summary = f"{summary} {len(affected_targets)} affected targets recorded."
    return {
        "code": code,
        "confidence": round(confidence, 2),
        "summary": summary.strip(),
    }


def build_observed_features(
    connectivity_assessment: dict[str, Any],
    runtime_summary: dict[str, Any],
    resolver_assessment: dict[str, Any],
    http_assessment: dict[str, Any],
    tls_assessment: dict[str, Any],
    route_assessment: dict[str, Any],
    trigger_fuzz_assessment: dict[str, Any],
    winner_summary: dict[str, Any],
) -> set[str]:
    features = {f"assessment:{connectivity_assessment['code']}"}
    for item in resolver_assessment["outcomes"]:
        features.add(f"dns_outcome:{slugify(item['value'])}")
    for item in resolver_assessment["comparisonSignals"]:
        features.add(f"dns_signal:{slugify(item['value'])}")
    if resolver_assessment["injectionSuspected"]:
        features.add("dns_injection:suspected")
    for item in http_assessment["statuses"]:
        features.add(f"http_status:{slugify(item['value'])}")
    for item in http_assessment["redirects"]:
        features.add(f"http_redirect:{slugify(item['value'])}")
    for item in tls_assessment["outcomes"]:
        features.add(f"tls_outcome:{slugify(item['value'])}")
    for item in tls_assessment["signals"]:
        features.add(f"tls_signal:{slugify(item['value'])}")
    for item in tls_assessment["errors"]:
        features.add(f"tls_error:{slugify(item['value'])}")
    for item in route_assessment["selectedBucketKinds"]:
        features.add(f"route_bucket:{slugify(item['value'])}")
    if route_assessment["routeSensitive"]:
        features.add("route_sensitive:true")
    for family, items in trigger_fuzz_assessment["changedFields"].items():
        for item in items:
            features.add(f"trigger:{slugify(family)}:{slugify(item['value'])}")
    service_status = runtime_summary.get("serviceStatus")
    if service_status:
        features.add(f"runtime_status:{slugify(service_status)}")
    if runtime_summary.get("lastNativeErrorHeadline"):
        features.add(f"runtime_error:{slugify(runtime_summary['lastNativeErrorHeadline'])}")
    if winner_summary.get("family"):
        features.add(f"winner_family:{slugify(winner_summary['family'])}")
    return features


def counter_to_sorted_items(counter: Counter[str]) -> list[dict[str, Any]]:
    return [
        {"value": value, "count": count}
        for value, count in sorted(counter.items(), key=lambda item: (-item[1], item[0]))
    ]


def normalize_http_response(value: str) -> str:
    normalized = value.strip().lower()
    if "moved permanently" in normalized:
        return "redirect_moved_permanently"
    if "temporary redirect" in normalized or "status_307" in normalized:
        return "redirect_temporary"
    if "forbidden" in normalized:
        return "http_forbidden"
    return slugify(normalized)


def normalize_redirect_location(value: str) -> str:
    candidate = value.strip().lower()
    candidate = candidate.removeprefix("http://").removeprefix("https://")
    candidate = candidate.split("/", 1)[0]
    if not candidate:
        return "redirect_unknown"
    return candidate


def normalize_route_summary(value: str) -> str:
    normalized = value.strip().lower()
    fragments = []
    for fragment in split_pipe_values(normalized):
        if "stable#0" in fragment:
            fragments.append("stable")
        elif "bucket#" in fragment and ":ok" in fragment:
            fragments.append("bucket_ok")
        elif "bind" in fragment:
            fragments.append("bind_failure")
        elif "timeout" in fragment:
            fragments.append("timeout")
        else:
            fragments.append(slugify(fragment))
    return "|".join(sorted(set(fragments))) or "route_unknown"


def normalize_local_address(value: str) -> str:
    candidate = value.strip()
    if ":" in candidate:
        host = candidate.rsplit(":", 1)[0]
        if host.startswith("[") and host.endswith("]"):
            host = host[1:-1]
        if "." in host:
            octets = host.split(".")
            if len(octets) == 4:
                return ".".join(octets[:3]) + ".0/24"
        if ":" in host:
            segments = host.split(":")
            return ":".join(segments[:4]) + "::/64"
    return "local_unknown"


def first_non_blank(values: Iterable[str | None]) -> str | None:
    for value in values:
        if value and value.strip():
            return value
    return None
