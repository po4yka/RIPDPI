#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
from pathlib import Path

def read_json(path: Path | None) -> dict | None:
    if path is None or not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def discover_benchmarks(criterion_dir: Path) -> dict[str, dict[str, float]]:
    results: dict[str, dict[str, float]] = {}
    if not criterion_dir.is_dir():
        return results
    for estimates_path in sorted(criterion_dir.rglob("new/estimates.json")):
        bench_dir = estimates_path.parent.parent
        bench_name = str(bench_dir.relative_to(criterion_dir))
        data = json.loads(estimates_path.read_text(encoding="utf-8"))
        mean_ns = data.get("mean", {}).get("point_estimate")
        median_ns = data.get("median", {}).get("point_estimate")
        if mean_ns is None or median_ns is None:
            continue
        results[bench_name] = {"mean_ns": mean_ns, "median_ns": median_ns}
    return results


def classify_criterion(benchmarks: dict[str, dict[str, float]]) -> dict:
    spotlight = {
        "nativeThroughput": benchmarks.get("relay/tcp-echo/relay/1MiB"),
        "runtimeHotPathRead": benchmarks.get("runtime-control/network-snapshot/read-only/current_network_snapshot"),
        "runtimeHotPathWriteRead": benchmarks.get("runtime-control/network-snapshot/write-then-read/update_network_snapshot"),
        "tcpRelaySetup": benchmarks.get("relay/connect-setup/socks5-connect/tcp-echo"),
        "lockContention": benchmarks.get("runtime-lock-contention/parallel/read-heavy"),
    }
    return {
        "benchmarks": benchmarks,
        "spotlight": spotlight,
    }


def summarize_size_report(report: dict | None) -> dict | None:
    if not report:
        return None
    if "totals" not in report:
        libraries = [
            {
                "abi": abi,
                "library": library,
                "currentSize": size,
            }
            for abi, abi_sizes in sorted(report.get("libraries", {}).items())
            for library, size in sorted(abi_sizes.items())
        ]
        libraries.sort(key=lambda item: int(item["currentSize"]), reverse=True)
        total_size = sum(entry["currentSize"] for entry in libraries)
        return {
            "totals": {
                "baselineSize": None,
                "currentSize": total_size,
                "deltaBytes": None,
                "status": "current-only",
            },
            "largestDeltas": libraries[:6],
        }
    return {
        "totals": report.get("totals"),
        "largestDeltas": [
            entry
            for entry in sorted(
                (entry for entry in report.get("libraries", []) if entry.get("deltaBytes") is not None),
                key=lambda item: abs(int(item["deltaBytes"])),
                reverse=True,
            )[:6]
        ],
    }


def summarize_bloat_report(report: dict | None) -> dict | None:
    if not report:
        return None
    return {
        "target": report.get("target"),
        "profile": report.get("profile"),
        "packages": {
            package: {
                "textSection": payload.get("textSection"),
                "likelyDrivers": payload.get("likelyDrivers", []),
                "topCrateGrowth": payload.get("topCrateGrowth", [])[:5],
            }
            for package, payload in report.get("packages", {}).items()
        },
    }


def build_snapshot(
    criterion_dir: Path,
    load_report: dict | None,
    wrapper_report: dict | None,
    debug_size_report: dict | None,
    release_size_report: dict | None,
    bloat_report: dict | None,
) -> dict:
    criterion = classify_criterion(discover_benchmarks(criterion_dir))
    memory_per_connection = None
    if load_report is not None:
        memory_per_connection = {
            "establishedConnections": load_report.get("establishedConnections"),
            "processRssGrowthBytesPerEstablishedConnection": load_report.get("processRssGrowthBytesPerEstablishedConnection"),
            "ripdpiThreadGrowthPerEstablishedConnection": load_report.get("ripdpiThreadGrowthPerEstablishedConnection"),
            "holdSeconds": load_report.get("holdSeconds"),
        }

    return {
        "version": 1,
        "unsafeAuditChecklist": "docs/native/unsafe-audit.md",
        "criterion": criterion,
        "memoryPerConnection": memory_per_connection,
        "engineWrapper": wrapper_report,
        "debugNativeSize": summarize_size_report(debug_size_report),
        "releaseNativeSize": summarize_size_report(release_size_report),
        "nativeBloat": summarize_bloat_report(bloat_report),
        "missingInputs": [
            name
            for name, value in (
                ("loadReport", load_report),
                ("wrapperReport", wrapper_report),
                ("debugSizeReport", debug_size_report),
                ("releaseSizeReport", release_size_report),
                ("bloatReport", bloat_report),
            )
            if value is None
        ],
    }


def render_markdown(snapshot: dict) -> str:
    lines = [
        "# Phase 0 Baseline Snapshot",
        "",
        f"- unsafe audit checklist: `{snapshot['unsafeAuditChecklist']}`",
    ]
    if snapshot["missingInputs"]:
        lines.append(f"- missing inputs: `{', '.join(snapshot['missingInputs'])}`")

    criterion = snapshot["criterion"]
    lines.extend(["", "## Criterion Spotlight", ""])
    for name, entry in criterion["spotlight"].items():
        if entry is None:
            lines.append(f"- {name}: `missing`")
            continue
        lines.append(f"- {name}: mean `{entry['mean_ns']:.0f}` ns, median `{entry['median_ns']:.0f}` ns")

    if snapshot["memoryPerConnection"] is not None:
        memory = snapshot["memoryPerConnection"]
        lines.extend(
            [
                "",
                "## Memory Per Connection",
                "",
                f"- established connections: `{memory['establishedConnections']}`",
                f"- RSS growth per established connection: `{memory['processRssGrowthBytesPerEstablishedConnection']}`",
                f"- ripdpi thread growth per established connection: `{memory['ripdpiThreadGrowthPerEstablishedConnection']}`",
                f"- hold seconds: `{memory['holdSeconds']}`",
            ],
        )

    if snapshot["engineWrapper"] is not None:
        wrapper = snapshot["engineWrapper"]
        lines.extend(
            [
                "",
                "## Engine Wrapper",
                "",
                f"- startup mean: `{wrapper['startup']['meanNs']}` ns",
                f"- shutdown mean: `{wrapper['shutdown']['meanNs']}` ns",
                f"- pollTelemetry mean: `{wrapper['pollTelemetry']['meanNs']}` ns",
            ],
        )

    for label, report in (("Debug Native Size", snapshot["debugNativeSize"]), ("Release Native Size", snapshot["releaseNativeSize"])):
        if report is None:
            continue
        totals = report["totals"]
        lines.extend(
            [
                "",
                f"## {label}",
                "",
                f"- baseline size: `{totals['baselineSize'] if totals['baselineSize'] is not None else 'n/a'}`",
                f"- current size: `{totals['currentSize']}`",
                f"- delta: `{totals['deltaBytes'] if totals['deltaBytes'] is not None else 'n/a'}`",
                f"- status: `{totals['status']}`",
            ],
        )

    if snapshot["nativeBloat"] is not None:
        lines.extend(["", "## Native Bloat Drivers", ""])
        for package, payload in snapshot["nativeBloat"]["packages"].items():
            drivers = payload.get("likelyDrivers", [])
            lines.append(f"- {package}: `{', '.join(drivers) if drivers else 'no dominant crate growth'}`")

    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the Phase 0 baseline snapshot from existing benchmark and CI artifacts.")
    parser.add_argument("--criterion-dir", default="native/rust/target/criterion")
    parser.add_argument("--load-report")
    parser.add_argument("--wrapper-report")
    parser.add_argument("--debug-size-report")
    parser.add_argument("--release-size-report")
    parser.add_argument("--bloat-report")
    parser.add_argument("--output-json", required=True)
    parser.add_argument("--output-md", required=True)
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    criterion_dir = (repo_root / args.criterion_dir).resolve()
    snapshot = build_snapshot(
        criterion_dir=criterion_dir,
        load_report=read_json((repo_root / args.load_report).resolve()) if args.load_report else None,
        wrapper_report=read_json((repo_root / args.wrapper_report).resolve()) if args.wrapper_report else None,
        debug_size_report=read_json((repo_root / args.debug_size_report).resolve()) if args.debug_size_report else None,
        release_size_report=read_json((repo_root / args.release_size_report).resolve()) if args.release_size_report else None,
        bloat_report=read_json((repo_root / args.bloat_report).resolve()) if args.bloat_report else None,
    )

    output_json = (repo_root / args.output_json).resolve()
    output_md = (repo_root / args.output_md).resolve()
    output_json.parent.mkdir(parents=True, exist_ok=True)
    output_md.parent.mkdir(parents=True, exist_ok=True)
    output_json.write_text(json.dumps(snapshot, indent=2) + "\n", encoding="utf-8")
    output_md.write_text(render_markdown(snapshot), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
