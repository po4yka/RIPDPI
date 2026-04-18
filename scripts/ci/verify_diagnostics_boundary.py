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
FORBIDDEN_ROOT_FILES = (
    "DiagnosticsArchiveBuildInfoProvider.kt",
    "DiagnosticsArchiveExporter.kt",
    "DiagnosticsArchiveFileStore.kt",
    "DiagnosticsArchiveModels.kt",
    "DiagnosticsArchiveRedactor.kt",
    "DiagnosticsArchiveRenderer.kt",
    "DiagnosticsArchiveZipWriter.kt",
    "DiagnosticsShareSummaryBuilder.kt",
    "DiagnosticsScanLaunchMetadata.kt",
    "DiagnosticsScanRequestFactory.kt",
    "DiagnosticsReportPersister.kt",
    "DiagnosticsServicesImpl.kt",
)
PACKAGE_EXPECTATIONS = {
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveBuildInfoProvider.kt":
        "package com.poyka.ripdpi.diagnostics.export",
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveCsvEntryBuilder.kt":
        "package com.poyka.ripdpi.diagnostics.export",
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveExporter.kt":
        "package com.poyka.ripdpi.diagnostics.export",
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveFileStore.kt":
        "package com.poyka.ripdpi.diagnostics.export",
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveJsonEntryBuilder.kt":
        "package com.poyka.ripdpi.diagnostics.export",
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveModels.kt":
        "package com.poyka.ripdpi.diagnostics.export",
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveRedactor.kt":
        "package com.poyka.ripdpi.diagnostics.export",
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveRenderer.kt":
        "package com.poyka.ripdpi.diagnostics.export",
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsArchiveZipWriter.kt":
        "package com.poyka.ripdpi.diagnostics.export",
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DiagnosticsShareSummaryBuilder.kt":
        "package com.poyka.ripdpi.diagnostics.export",
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/export/DefaultDiagnosticsShareService.kt":
        "package com.poyka.ripdpi.diagnostics.export",
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DefaultDiagnosticsBootstrapper.kt":
        "package com.poyka.ripdpi.diagnostics.application",
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DefaultDiagnosticsResolverActions.kt":
        "package com.poyka.ripdpi.diagnostics.application",
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsRecommendationStore.kt":
        "package com.poyka.ripdpi.diagnostics.application",
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanLaunchMetadata.kt":
        "package com.poyka.ripdpi.diagnostics.application",
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/application/DiagnosticsScanRequestFactory.kt":
        "package com.poyka.ripdpi.diagnostics.application",
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/finalization/DiagnosticsReportPersister.kt":
        "package com.poyka.ripdpi.diagnostics.finalization",
    "core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/queries/DefaultDiagnosticsDetailLoader.kt":
        "package com.poyka.ripdpi.diagnostics.queries",
}


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


def extracted_file_violations(repo_root: Path) -> list[Violation]:
    package_root = repo_root / DIAGNOSTICS_PACKAGE_ROOT
    violations: list[Violation] = []
    for file_name in FORBIDDEN_ROOT_FILES:
        root_file = package_root / file_name
        if root_file.exists():
            violations.append(
                Violation(
                    path=root_file.relative_to(repo_root).as_posix(),
                    message="phase 17 extracted file must not remain in diagnostics root package",
                )
            )
    for relative_path, expected_package in PACKAGE_EXPECTATIONS.items():
        file_path = repo_root / relative_path
        if not file_path.is_file():
            violations.append(
                Violation(
                    path=relative_path,
                    message="required extracted diagnostics boundary file is missing",
                )
            )
            continue
        source = file_path.read_text(encoding="utf-8")
        if not source.startswith(expected_package):
            violations.append(
                Violation(
                    path=relative_path,
                    message=f"expected file package declaration '{expected_package}'",
                )
            )
    return violations


def collect_violations(repo_root: Path) -> list[Violation]:
    return [
        *gradle_dependency_violations(repo_root),
        *source_reference_violations(repo_root),
        *package_layout_violations(repo_root),
        *extracted_file_violations(repo_root),
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
