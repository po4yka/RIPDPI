#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tomllib
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Sequence

from check_file_loc_limits import (
    count_code_lines,
    git_ls_files,
    is_compose_source,
    is_included_source,
    top_functions,
)


DEFAULT_BASELINE = "config/static/architecture-health-baseline.json"
DEFAULT_REPORT_DIR = "build/reports/architecture"

INTERNAL_CRATE_PREFIXES = ("ripdpi-", "local-network-fixture")

DEPENDENCY_HUB_LIMITS = {
    "ripdpi-android": (14, 2),
    "ripdpi-proxy-runtime": (16, 2),
    "ripdpi-monitor-engine": (12, 3),
    "ripdpi-proxy-runtime-adapter": (13, 3),
    "ripdpi-diagnostics-runner": (8, 3),
    "ripdpi-runtime-services": (8, 3),
    "ripdpi-relay-core": (7, 3),
}

DISCOURAGED_EDGES = {
    "ripdpi-android": {
        "ripdpi-proxy-runtime": 2,
        "ripdpi-monitor-engine": 2,
        "ripdpi-dns-resolver": 2,
        "ripdpi-diagnostics-dns": 3,
        "ripdpi-diagnostics-contracts": 3,
        "ripdpi-runtime-platform": 3,
        "ripdpi-runtime-strategy": 3,
    },
    "ripdpi-proxy-runtime": {
        "ripdpi-runtime-adaptive": 2,
        "ripdpi-runtime-policy": 2,
        "ripdpi-runtime-strategy": 2,
        "ripdpi-dns-resolver": 3,
        "ripdpi-failure-classifier": 3,
        "ripdpi-ws-bootstrap": 3,
        "ripdpi-ws-tunnel": 3,
    },
    "ripdpi-monitor-engine": {
        "ripdpi-diagnostics-candidates": 3,
        "ripdpi-diagnostics-classification": 3,
        "ripdpi-diagnostics-dns": 3,
        "ripdpi-diagnostics-http": 3,
        "ripdpi-diagnostics-runner": 3,
        "ripdpi-diagnostics-telegram": 3,
        "ripdpi-diagnostics-tls": 3,
        "ripdpi-diagnostics-transport": 3,
        "ripdpi-failure-classifier": 3,
        "ripdpi-proxy-config": 3,
    },
}

SOURCE_LOC_LIMITS = {
    "rust": (1500, 3),
    "kotlin": (700, 3),
    "compose": (1000, 3),
}

ROOT_FACADE_EXPORT_LIMIT = 10
LONG_FUNCTION_LIMITS = {
    "rust": (180, 3),
    "kotlin": (120, 3),
    "compose": (100, 3),
}

FEATURE_FAMILIES = {
    "adaptive": ("adaptive", "fallback", "strategy"),
    "desync": ("desync", "quic", "fake", "hostfake", "entropy"),
    "diagnostics": ("diagnostics", "probe", "scan", "audit"),
    "dns": ("dns", "resolver", "doh", "dot", "dnscrypt", "ech"),
    "relay": ("relay", "upstream", "cloudflare", "xhttp", "finalmask"),
    "routing": ("routing", "route", "protect", "directpath", "direct_path"),
    "telemetry": ("telemetry", "metric", "event", "snapshot"),
    "tunnel": ("tunnel", "tun", "socks"),
    "warp": ("warp", "zero_trust", "zerotrust"),
}

KOTLIN_FEATURE_SPREAD_LIMITS = {
    "app/src/main/kotlin/com/poyka/ripdpi/activities/": (5, 3),
    "app/src/main/kotlin/com/poyka/ripdpi/ui/screens/": (5, 3),
    "app/src/main/kotlin/com/poyka/ripdpi/ui/theme/": (5, 3),
    "core/service/src/main/kotlin/com/poyka/ripdpi/services/": (5, 2),
}

SUPPRESSION_TOKENS = ("LongMethod", "CyclomaticComplexMethod", "LongParameterList", "LargeClass", "TooManyFunctions")


@dataclass(frozen=True)
class Indicator:
    id: str
    rule: str
    priority: int
    path: str
    message: str
    metric_name: str
    metric_value: int
    limit: int
    suggestion: str


@dataclass(frozen=True)
class BaselineEntry:
    id: str
    rule: str
    priority: int
    path: str
    message: str
    metric_name: str
    baseline_value: int
    limit: int
    suggestion: str


def normalize_id(value: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_.:-]+", "-", value).strip("-").lower()


def git_staged_paths(repo_root: Path) -> list[str]:
    completed = subprocess.run(
        ["git", "diff", "--cached", "--name-only", "--diff-filter=ACM"],
        cwd=repo_root,
        check=True,
        capture_output=True,
        text=True,
    )
    return [line for line in completed.stdout.splitlines() if line]


def read_toml(path: Path) -> dict:
    return tomllib.loads(path.read_text(encoding="utf-8"))


def dependency_names(table: object) -> set[str]:
    if not isinstance(table, dict):
        return set()
    return {key for key in table if key.startswith(INTERNAL_CRATE_PREFIXES)}


def manifest_dependencies(manifest: Path, *, production_only: bool = False) -> set[str]:
    data = read_toml(manifest)
    deps: set[str] = set()
    dependency_sections = ("dependencies",) if production_only else ("dependencies", "dev-dependencies", "build-dependencies")
    for key in dependency_sections:
        deps.update(dependency_names(data.get(key)))
    for target in data.get("target", {}).values():
        if isinstance(target, dict):
            for key in dependency_sections:
                deps.update(dependency_names(target.get(key)))
    return deps


def crate_name(manifest: Path) -> str:
    package = read_toml(manifest).get("package", {})
    if isinstance(package, dict) and isinstance(package.get("name"), str):
        return str(package["name"])
    return manifest.parent.name


def iter_crate_manifests(repo_root: Path, tracked_paths: Sequence[str] | None) -> list[Path]:
    all_manifests = sorted((repo_root / "native/rust/crates").glob("*/Cargo.toml"))
    if tracked_paths is None:
        return all_manifests
    changed = {Path(path) for path in tracked_paths}
    changed_crates = {path.parent for path in changed if path.name == "Cargo.toml" and "native" in path.parts}
    return [manifest for manifest in all_manifests if manifest.relative_to(repo_root).parent in changed_crates]


def add_indicator(
    indicators: list[Indicator],
    *,
    rule: str,
    priority: int,
    path: str,
    subject: str,
    message: str,
    metric_name: str,
    metric_value: int,
    limit: int,
    suggestion: str,
) -> None:
    indicators.append(
        Indicator(
            id=normalize_id(f"{rule}:{path}:{subject}"),
            rule=rule,
            priority=priority,
            path=path,
            message=message,
            metric_name=metric_name,
            metric_value=metric_value,
            limit=limit,
            suggestion=suggestion,
        )
    )


def collect_dependency_indicators(repo_root: Path, tracked_paths: Sequence[str] | None) -> list[Indicator]:
    indicators: list[Indicator] = []
    for manifest in iter_crate_manifests(repo_root, tracked_paths):
        rel_path = manifest.relative_to(repo_root).as_posix()
        name = crate_name(manifest)
        deps = sorted(manifest_dependencies(manifest, production_only=True))

        if name in DEPENDENCY_HUB_LIMITS:
            limit, priority = DEPENDENCY_HUB_LIMITS[name]
            if len(deps) > limit:
                add_indicator(
                    indicators,
                    rule="dependency-hub",
                    priority=priority,
                    path=rel_path,
                    subject=name,
                    message=f"{name} links {len(deps)} internal crates",
                    metric_name="internalDependencyCount",
                    metric_value=len(deps),
                    limit=limit,
                    suggestion="Move feature composition behind narrower ports or adapter crates.",
                )

        discouraged = DISCOURAGED_EDGES.get(name, {})
        for dep in deps:
            priority = discouraged.get(dep)
            if priority is None:
                continue
            add_indicator(
                indicators,
                rule="discouraged-dependency-edge",
                priority=priority,
                path=rel_path,
                subject=f"{name}->{dep}",
                message=f"{name} depends directly on {dep}",
                metric_name="edgePresent",
                metric_value=1,
                limit=0,
                suggestion="Depend on a narrow interface crate or feature adapter instead of the concrete lane.",
            )
    return indicators


def source_kind(path: Path, text: str) -> str:
    if path.suffix == ".rs":
        return "rust"
    return "compose" if is_compose_source(text) else "kotlin"


def iter_source_files(repo_root: Path, tracked_paths: Sequence[str] | None) -> list[Path]:
    paths = tracked_paths if tracked_paths is not None else git_ls_files(repo_root)
    return sorted(
        {
            Path(path)
            for path in paths
            if is_included_source(Path(path)) and (repo_root / path).is_file()
            and Path(path).name not in {"tests.rs", "test.rs"}
            and "src/test" not in Path(path).as_posix()
            and "src/androidTest" not in Path(path).as_posix()
        }
    )


def count_root_exports(text: str) -> int:
    return len(re.findall(r"^\s*pub\s+(?:mod|use)\b", text, re.MULTILINE))


def feature_families(text: str) -> set[str]:
    lowered = text.lower()
    return {
        family
        for family, tokens in FEATURE_FAMILIES.items()
        if any(token in lowered for token in tokens)
    }


def kotlin_feature_limit(path: str) -> tuple[int, int] | None:
    for prefix, limit in KOTLIN_FEATURE_SPREAD_LIMITS.items():
        if path.startswith(prefix):
            return limit
    return None


def suppression_count(text: str) -> int:
    return sum(text.count(token) for token in SUPPRESSION_TOKENS)


def collect_source_indicators(repo_root: Path, tracked_paths: Sequence[str] | None) -> list[Indicator]:
    indicators: list[Indicator] = []
    for rel in iter_source_files(repo_root, tracked_paths):
        path = repo_root / rel
        rel_path = rel.as_posix()
        text = path.read_text(encoding="utf-8", errors="replace")
        kind = source_kind(rel, text)
        language = "rust" if kind == "rust" else "kotlin"
        measured_loc = count_code_lines(text, language)

        loc_limit, loc_priority = SOURCE_LOC_LIMITS[kind]
        if measured_loc > loc_limit:
            add_indicator(
                indicators,
                rule="oversized-source-file",
                priority=loc_priority,
                path=rel_path,
                subject=kind,
                message=f"{rel_path} has {measured_loc} code lines",
                metric_name="codeLines",
                metric_value=measured_loc,
                limit=loc_limit,
                suggestion="Split unrelated responsibilities into focused modules before growing this file.",
            )

        fn_limit, fn_priority = LONG_FUNCTION_LIMITS[kind]
        longest = top_functions(path, language, top_n=1)
        if longest and longest[0][1] > fn_limit and measured_loc > 500:
            add_indicator(
                indicators,
                rule="long-function-or-composable",
                priority=fn_priority,
                path=rel_path,
                subject=longest[0][0],
                message=f"{longest[0][0]} spans {longest[0][1]} lines",
                metric_name="functionLines",
                metric_value=longest[0][1],
                limit=fn_limit,
                suggestion="Extract state, rendering, parsing, or execution phases into named helpers.",
            )

        if rel.name in ("lib.rs", "main.rs"):
            exports = count_root_exports(text)
            if exports > ROOT_FACADE_EXPORT_LIMIT:
                add_indicator(
                    indicators,
                    rule="broad-root-facade",
                    priority=3,
                    path=rel_path,
                    subject=rel.name,
                    message=f"root file exposes {exports} public modules/re-exports",
                    metric_name="rootExports",
                    metric_value=exports,
                    limit=ROOT_FACADE_EXPORT_LIMIT,
                    suggestion="Keep the root as a small facade and move implementation families into modules.",
                )

        if kind in {"kotlin", "compose"}:
            spread_limit = kotlin_feature_limit(rel_path)
            families = feature_families(text)
            if spread_limit is not None and len(families) > spread_limit[0]:
                add_indicator(
                    indicators,
                    rule="kotlin-feature-spread",
                    priority=spread_limit[1],
                    path=rel_path,
                    subject=",".join(sorted(families)),
                    message=f"{rel_path} references {len(families)} feature families",
                    metric_name="featureFamilyCount",
                    metric_value=len(families),
                    limit=spread_limit[0],
                    suggestion="Move feature-specific models, binders, or lifecycle work behind feature modules.",
                )

            suppressions = suppression_count(text)
            if suppressions > 0 and measured_loc > 300 and spread_limit is not None:
                add_indicator(
                    indicators,
                    rule="complexity-suppression",
                    priority=3,
                    path=rel_path,
                    subject="suppression",
                    message=f"{rel_path} carries {suppressions} complexity suppressions",
                    metric_name="suppressionCount",
                    metric_value=suppressions,
                    limit=0,
                    suggestion="Use suppressions as a refactor marker and split the suppressed surface.",
                )

    return indicators


def collect_indicators(repo_root: Path, tracked_paths: Sequence[str] | None = None) -> list[Indicator]:
    indicators = collect_dependency_indicators(repo_root, tracked_paths)
    indicators.extend(collect_source_indicators(repo_root, tracked_paths))
    return sorted(indicators, key=lambda indicator: (indicator.priority, indicator.rule, indicator.path, indicator.id))


def read_baseline(path: Path) -> dict[str, BaselineEntry]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    entries = raw.get("entries")
    if not isinstance(entries, list):
        raise ValueError(f"Architecture baseline must contain an entries list: {path}")

    baseline: dict[str, BaselineEntry] = {}
    for raw_entry in entries:
        entry = BaselineEntry(
            id=raw_entry["id"],
            rule=raw_entry["rule"],
            priority=int(raw_entry["priority"]),
            path=raw_entry["path"],
            message=raw_entry["message"],
            metric_name=raw_entry["metricName"],
            baseline_value=int(raw_entry["baselineValue"]),
            limit=int(raw_entry["limit"]),
            suggestion=raw_entry["suggestion"],
        )
        if entry.id in baseline:
            raise ValueError(f"Duplicate architecture baseline entry: {entry.id}")
        baseline[entry.id] = entry
    return baseline


def baseline_payload(indicators: Iterable[Indicator]) -> dict[str, object]:
    return {
        "version": 1,
        "entries": [
            {
                "id": indicator.id,
                "rule": indicator.rule,
                "priority": indicator.priority,
                "path": indicator.path,
                "message": indicator.message,
                "metricName": indicator.metric_name,
                "baselineValue": indicator.metric_value,
                "limit": indicator.limit,
                "suggestion": indicator.suggestion,
            }
            for indicator in sorted(indicators, key=lambda item: item.id)
        ],
    }


def indicator_json(indicator: Indicator) -> dict[str, object]:
    return {
        "id": indicator.id,
        "rule": indicator.rule,
        "priority": indicator.priority,
        "path": indicator.path,
        "message": indicator.message,
        "metricName": indicator.metric_name,
        "metricValue": indicator.metric_value,
        "limit": indicator.limit,
        "suggestion": indicator.suggestion,
    }


def evaluate_indicators(
    indicators: Sequence[Indicator],
    baseline: dict[str, BaselineEntry],
    *,
    enforce_stale: bool,
) -> dict[str, object]:
    current = {indicator.id: indicator for indicator in indicators}
    new_indicators = [indicator_json(item) for item in indicators if item.id not in baseline]
    worsened: list[dict[str, object]] = []
    baseline_indicators: list[dict[str, object]] = []

    for indicator in indicators:
        baseline_entry = baseline.get(indicator.id)
        if baseline_entry is None:
            continue
        payload = indicator_json(indicator)
        payload["baselineValue"] = baseline_entry.baseline_value
        baseline_indicators.append(payload)
        if indicator.metric_value > baseline_entry.baseline_value:
            worsened.append(payload)

    stale = []
    if enforce_stale:
        stale = [
            {
                "id": item.id,
                "rule": item.rule,
                "priority": item.priority,
                "path": item.path,
                "message": item.message,
                "metricName": item.metric_name,
                "baselineValue": item.baseline_value,
                "limit": item.limit,
                "suggestion": item.suggestion,
            }
            for item in baseline.values()
            if item.id not in current
        ]

    return {
        "counts": {
            "currentIndicators": len(indicators),
            "baselineIndicators": len(baseline_indicators),
            "newIndicators": len(new_indicators),
            "worsenedBaselineEntries": len(worsened),
            "staleBaselineEntries": len(stale),
        },
        "currentIndicators": sorted([indicator_json(item) for item in indicators], key=lambda item: str(item["id"])),
        "baselineIndicators": sorted(baseline_indicators, key=lambda item: str(item["id"])),
        "newIndicators": sorted(new_indicators, key=lambda item: str(item["id"])),
        "worsenedBaselineEntries": sorted(worsened, key=lambda item: str(item["id"])),
        "staleBaselineEntries": sorted(stale, key=lambda item: str(item["id"])),
    }


def render_markdown(results: dict[str, object]) -> str:
    counts = results["counts"]
    assert isinstance(counts, dict)
    lines = [
        "# Architecture Health",
        "",
        f"- current indicators: `{counts['currentIndicators']}`",
        f"- baseline indicators: `{counts['baselineIndicators']}`",
        f"- new indicators: `{counts['newIndicators']}`",
        f"- worsened baseline entries: `{counts['worsenedBaselineEntries']}`",
        f"- stale baseline entries: `{counts['staleBaselineEntries']}`",
        "",
    ]

    for title, key in (
        ("New Indicators", "newIndicators"),
        ("Worsened Baseline Entries", "worsenedBaselineEntries"),
        ("Stale Baseline Entries", "staleBaselineEntries"),
        ("Baseline Indicators", "baselineIndicators"),
    ):
        rows = results[key]
        assert isinstance(rows, list)
        lines.extend([f"## {title}", ""])
        if not rows:
            lines.extend(["None.", ""])
            continue
        lines.extend(["| Priority | Rule | Path | Metric | Suggestion |", "| --- | --- | --- | --- | --- |"])
        for row in rows:
            metric = f"`{row['metricName']}={row.get('metricValue', row.get('baselineValue'))}`"
            lines.append(
                f"| P{row['priority']} | `{row['rule']}` | `{row['path']}` | {metric} | {row['suggestion']} |"
            )
        lines.append("")

    return "\n".join(lines)


def format_summary(results: dict[str, object]) -> str:
    counts = results["counts"]
    assert isinstance(counts, dict)
    lines = [
        "Architecture health",
        f"Current indicators: {counts['currentIndicators']}",
        f"Baseline indicators: {counts['baselineIndicators']}",
        f"New indicators: {counts['newIndicators']}",
        f"Worsened baseline entries: {counts['worsenedBaselineEntries']}",
        f"Stale baseline entries: {counts['staleBaselineEntries']}",
    ]
    for key, label in (
        ("newIndicators", "new"),
        ("worsenedBaselineEntries", "worsened"),
        ("staleBaselineEntries", "stale"),
    ):
        rows = results[key]
        assert isinstance(rows, list)
        for row in rows:
            lines.append(f"  - {label}: P{row['priority']} {row['path']} {row['rule']} ({row['id']})")
    return "\n".join(lines)


def write_reports(report_dir: Path, results: dict[str, object]) -> None:
    report_dir.mkdir(parents=True, exist_ok=True)
    (report_dir / "architecture-health.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    (report_dir / "architecture-health.md").write_text(render_markdown(results), encoding="utf-8")
    (report_dir / "summary.txt").write_text(format_summary(results) + "\n", encoding="utf-8")


def should_fail(results: dict[str, object]) -> bool:
    counts = results["counts"]
    assert isinstance(counts, dict)
    return any(
        int(counts[key]) > 0
        for key in ("newIndicators", "worsenedBaselineEntries", "staleBaselineEntries")
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify architecture health indicators against the checked baseline.")
    parser.add_argument("--baseline", default=DEFAULT_BASELINE, help="Checked-in architecture baseline JSON.")
    parser.add_argument("--report-dir", default=DEFAULT_REPORT_DIR, help="Directory for generated reports.")
    parser.add_argument("--dump-baseline", action="store_true", help="Print the current architecture baseline payload.")
    parser.add_argument("--staged", action="store_true", help="Only check staged source and Cargo manifest changes.")
    parser.add_argument("--paths", nargs="*", help="Optional repo-relative paths to check.")
    parser.add_argument("--check", action="store_true", help="Validate against the baseline. This is the default mode.")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    tracked_paths = args.paths
    enforce_stale = True
    if args.staged:
        tracked_paths = git_staged_paths(repo_root)
        enforce_stale = False
    elif args.paths is not None:
        enforce_stale = False

    indicators = collect_indicators(repo_root, tracked_paths)

    if args.dump_baseline:
        print(json.dumps(baseline_payload(indicators), indent=2))
        return 0

    baseline_path = (repo_root / args.baseline).resolve()
    if not baseline_path.is_file():
        raise ValueError(f"Architecture baseline not found: {baseline_path}")

    results = evaluate_indicators(indicators, read_baseline(baseline_path), enforce_stale=enforce_stale)
    write_reports((repo_root / args.report_dir).resolve(), results)
    print(format_summary(results))
    return 1 if should_fail(results) else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"Architecture health verification failed: {exc}", file=sys.stderr)
        raise
