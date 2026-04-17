#!/usr/bin/env python3

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
DIAGNOSTICS_BUILD_FILE = Path("core/diagnostics/build.gradle.kts")
DIAGNOSTICS_MAIN_ROOT = Path("core/diagnostics/src/main")
DIAGNOSTICS_PACKAGE_ROOT = (
    DIAGNOSTICS_MAIN_ROOT / "kotlin/com/poyka/ripdpi/diagnostics"
)
SERVICE_GRADLE_DEP_RE = re.compile(r'project\(\s*":core:service"\s*\)')
SERVICE_PACKAGE_REF_RE = re.compile(r"\bcom\.poyka\.ripdpi\.services\b")
SOURCE_SUFFIXES = {".kt", ".kts", ".java"}
REQUIRED_DIAGNOSTICS_DIRS = (
    "application",
    "domain",
    "export",
    "finalization",
    "model",
    "presentation",
    "queries",
    "recommendation",
)


@dataclass(frozen=True)
class Violation:
    path: str
    message: str


def gradle_dependency_violations(repo_root: Path) -> list[Violation]:
    build_file = repo_root / DIAGNOSTICS_BUILD_FILE
    source = build_file.read_text(encoding="utf-8")
    if SERVICE_GRADLE_DEP_RE.search(source):
        return [
            Violation(
                path=DIAGNOSTICS_BUILD_FILE.as_posix(),
                message="diagnostics module must not depend on :core:service",
            )
        ]
    return []


def source_reference_violations(repo_root: Path) -> list[Violation]:
    violations: list[Violation] = []
    source_root = repo_root / DIAGNOSTICS_MAIN_ROOT
    for source_path in sorted(source_root.rglob("*")):
        if source_path.suffix not in SOURCE_SUFFIXES or not source_path.is_file():
            continue
        source = source_path.read_text(encoding="utf-8")
        if SERVICE_PACKAGE_REF_RE.search(source):
            violations.append(
                Violation(
                    path=source_path.relative_to(repo_root).as_posix(),
                    message="diagnostics main sources must not reference com.poyka.ripdpi.services",
                )
            )
    return violations


def package_layout_violations(repo_root: Path) -> list[Violation]:
    package_root = repo_root / DIAGNOSTICS_PACKAGE_ROOT
    violations: list[Violation] = []
    for relative_dir in REQUIRED_DIAGNOSTICS_DIRS:
        if not (package_root / relative_dir).is_dir():
            violations.append(
                Violation(
                    path=DIAGNOSTICS_PACKAGE_ROOT.as_posix(),
                    message=f"diagnostics package layout missing required directory '{relative_dir}'",
                )
            )
    return violations


def collect_violations(repo_root: Path) -> list[Violation]:
    return [
        *gradle_dependency_violations(repo_root),
        *source_reference_violations(repo_root),
        *package_layout_violations(repo_root),
    ]


def format_summary(violations: list[Violation]) -> str:
    lines = ["Diagnostics boundary verification", f"Violations: {len(violations)}"]
    for violation in violations:
        lines.append(f"  - {violation.path}: {violation.message}")
    return "\n".join(lines)


def main() -> int:
    violations = collect_violations(REPO_ROOT)
    print(format_summary(violations))
    return 1 if violations else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"Diagnostics boundary verification failed: {exc}", file=sys.stderr)
        raise
