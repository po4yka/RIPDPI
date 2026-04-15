#!/usr/bin/env python3

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
BASELINE_FILE = Path(__file__).resolve().parent / "module-deps-baseline.json"

# Only production dependency configurations; exclude test variants.
PROD_DEP_RE = re.compile(
    r"^[ \t]*(?!testImplementation|androidTestImplementation|testApi|androidTestApi)"
    r"(?:implementation|api|compileOnly)"
    r"\s*\(\s*project\s*\(\s*\"([^\"]+)\"\s*\)\s*\)",
    re.MULTILINE,
)

MODULES: dict[str, str] = {
    ":app": "app/build.gradle.kts",
    ":core:diagnostics": "core/diagnostics/build.gradle.kts",
    ":core:service": "core/service/build.gradle.kts",
}


@dataclass(frozen=True)
class ModuleResult:
    module: str
    current: int
    baseline: int

    @property
    def delta(self) -> int:
        return self.current - self.baseline

    @property
    def exceeded(self) -> bool:
        return self.current > self.baseline


def count_project_deps(repo_root: Path, gradle_path: str) -> int:
    build_file = repo_root / gradle_path
    source = build_file.read_text(encoding="utf-8")
    return len(PROD_DEP_RE.findall(source))


def load_baseline() -> dict[str, int]:
    return json.loads(BASELINE_FILE.read_text(encoding="utf-8"))


def collect_results(repo_root: Path, baseline: dict[str, int]) -> list[ModuleResult]:
    results: list[ModuleResult] = []
    for module, gradle_path in MODULES.items():
        current = count_project_deps(repo_root, gradle_path)
        base = baseline[module]
        results.append(ModuleResult(module=module, current=current, baseline=base))
    return results


def format_summary(results: list[ModuleResult]) -> str:
    violations = [r for r in results if r.exceeded]
    lines = [
        "Module dependency guard",
        f"Modules checked: {len(results)}  Violations: {len(violations)}",
    ]
    for result in results:
        status = "FAIL" if result.exceeded else "ok"
        lines.append(
            f"  [{status}] {result.module}: current={result.current}"
            f"  baseline={result.baseline}  delta={result.delta:+d}"
        )
    if violations:
        lines.append("")
        lines.append("Dependency count exceeds baseline for:")
        for r in violations:
            lines.append(
                f"  {r.module}: {r.current} > {r.baseline} (delta {r.delta:+d})"
                " -- remove new project(...) deps or update the roadmap task"
            )
    return "\n".join(lines)


def main() -> int:
    baseline = load_baseline()
    results = collect_results(REPO_ROOT, baseline)
    print(format_summary(results))
    violations = [r for r in results if r.exceeded]
    return 1 if violations else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"Module dependency guard failed: {exc}", file=sys.stderr)
        raise
