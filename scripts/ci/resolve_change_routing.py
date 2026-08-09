#!/usr/bin/env python3
"""Classify a CI change set conservatively for workflow routing.

Every path must belong to one explicitly-maintained route.  A mixed, empty, or
unknown change set is deliberately routed to the full CI matrix.
"""

from __future__ import annotations

import argparse
from pathlib import Path
from typing import Final


ROUTE_FULL: Final = "full"
ROUTE_DOCUMENTATION: Final = "documentation"
ROUTE_ANDROID: Final = "android"
ROUTE_RUST_NATIVE: Final = "rust-native"
ROUTE_RELEASE_BUILD_LOGIC: Final = "release-build-logic"
ROUTE_FIXTURES: Final = "fixtures"
ROUTE_WORKFLOW: Final = "workflow"

KNOWN_ROUTES: Final = frozenset(
    {
        ROUTE_DOCUMENTATION,
        ROUTE_ANDROID,
        ROUTE_RUST_NATIVE,
        ROUTE_RELEASE_BUILD_LOGIC,
        ROUTE_FIXTURES,
        ROUTE_WORKFLOW,
    }
)

ANDROID_PREFIXES: Final = (
    "app/",
    "core/data/",
    "core/diagnostics/",
    "core/diagnostics-data/",
    "core/service/",
)

ANDROID_FILES: Final = frozenset(
    {
        "scripts/ci/run-android-so-bind-physical-e2e.sh",
        "scripts/tests/test_android_device_session.py",
        "test-lab/scripts/android-device-session.py",
        "test-lab/scripts/run-mock-relay-matrix.sh",
        "test-lab/scripts/run-proxy-e2e.sh",
        "test-lab/scripts/run-rooted-emulator-netem.sh",
        "test-lab/scripts/run-vpn-e2e.sh",
    }
)

RELEASE_BUILD_LOGIC_PREFIXES: Final = (
    ".github/actions/setup-android-rust/",
    "build-logic/",
    "core/engine/",
    "gradle/",
    "quality/release-gates/",
)

RELEASE_BUILD_LOGIC_FILES: Final = frozenset(
    {
        ".github/workflows/ci.yml",
        ".github/workflows/release-candidate.yml",
        ".github/workflows/release.yml",
        "build.gradle.kts",
        "gradle.properties",
        "gradlew",
        "gradlew.bat",
        "scripts/ci/resolve_change_routing.py",
        "settings.gradle.kts",
    }
)

FIXTURE_WORKFLOW_FILES: Final = frozenset(
    {
        ".github/workflows/fleet-fixtures.yml",
        ".github/workflows/harness-checks.yml",
        ".github/workflows/local-network-lab.yml",
        ".github/workflows/phase16-matrix.yml",
    }
)

RELEASE_BUILD_LOGIC_FILENAMES: Final = frozenset(
    {
        "AndroidManifest.xml",
        "build.gradle.kts",
        "consumer-rules.pro",
        "proguard-rules.pro",
    }
)

WORKFLOW_FILES: Final = frozenset(
    {
        ".github/workflows/codeql-tracker.yml",
        ".github/workflows/codeql.yml",
        ".github/workflows/i18n-export.yml",
        ".github/workflows/i18n-locale-drift.yml",
        ".github/workflows/labeler.yml",
        ".github/workflows/secret-scan.yml",
        ".pinact.yaml",
        "scripts/ci/run-zizmor.sh",
        "scripts/tests/test_ci_native_dependency_graph.py",
        "scripts/tests/test_ci_tool_pinning.py",
        "scripts/tests/test_nightly_coverage_selection.py",
        "scripts/tests/test_release_artifact_uploads.py",
        "scripts/tests/test_release_candidate_manifest.py",
        "scripts/tests/test_release_p0_contracts.py",
        "scripts/tests/test_release_p1_contracts.py",
        "scripts/tests/test_resolve_change_routing.py",
        "scripts/tests/test_path_filtered_pr_checks.py",
    }
)

FAST_FIXTURE_PREFIXES: Final = ("core/data/src/test/resources/fleet-fixtures/",)

FAST_FIXTURE_FILES: Final = frozenset(
    {
        "contract-fixtures/phase16_lab_matrix.json",
        "scripts/tests/test_netem_transaction.py",
    }
)


def is_documentation_path(path: str) -> bool:
    return path.startswith("docs/") or (
        path.startswith("README") and path.endswith(".md") and "/" not in path
    )


def is_documentation_only(paths: list[str]) -> bool:
    return bool(paths) and all(is_documentation_path(path) for path in paths)


def is_fixture_path(path: str) -> bool:
    return (
        path in FAST_FIXTURE_FILES
        or path.startswith(FAST_FIXTURE_PREFIXES)
        or path.startswith("test-lab/chaos/netem/")
    )


def is_workflow_configuration_path(path: str) -> bool:
    return path.startswith(".github/actions/") or path.startswith(".github/workflows/")


def route_for_path(path: str) -> str | None:
    """Return an explicit route for a safe repository-relative path only."""
    candidate = Path(path)
    if candidate.is_absolute() or ".." in candidate.parts:
        return None
    if is_documentation_path(path):
        return ROUTE_DOCUMENTATION
    if is_fixture_path(path):
        return ROUTE_FIXTURES
    if {"fixture", "fixtures", "golden", "goldens"}.intersection(candidate.parts):
        return None
    if path in FIXTURE_WORKFLOW_FILES:
        return ROUTE_FIXTURES
    if (
        path in RELEASE_BUILD_LOGIC_FILES
        or candidate.name in RELEASE_BUILD_LOGIC_FILENAMES
        or candidate.suffix == ".proto"
        or path.startswith(RELEASE_BUILD_LOGIC_PREFIXES)
    ):
        return ROUTE_RELEASE_BUILD_LOGIC
    if path.startswith("native/rust/"):
        return ROUTE_RUST_NATIVE
    if path in ANDROID_FILES or path.startswith(ANDROID_PREFIXES):
        return ROUTE_ANDROID
    if path in WORKFLOW_FILES:
        return ROUTE_WORKFLOW
    return None


def classify_change_paths(paths: list[str]) -> str:
    """Choose a route, failing closed for empty, unknown, or mixed changes."""
    if not paths:
        return ROUTE_FULL

    routes = {route_for_path(path) for path in paths}
    if None in routes or len(routes) != 1:
        return ROUTE_FULL

    route = routes.pop()
    if route not in KNOWN_ROUTES:
        return ROUTE_FULL
    return route


def routing_outputs(paths: list[str]) -> dict[str, str]:
    """Map a fail-closed route to the GitHub Actions job switches."""
    route = classify_change_paths(paths)
    # Workflow and fixture routes have dedicated contract gates. Source-only
    # Android and Rust routes use their owned lanes; mixed, release/build-logic,
    # empty, and unknown changes remain fail-closed on the complete CI graph.
    run_full_ci = route not in {
        ROUTE_DOCUMENTATION,
        ROUTE_ANDROID,
        ROUTE_RUST_NATIVE,
        ROUTE_FIXTURES,
        ROUTE_WORKFLOW,
    }
    # Coverage is source-owned: Android/Kotlin and Rust/native reports are
    # generated by separate toolchains and should only run for relevant routes.
    run_kotlin_coverage = route in {
        ROUTE_FULL,
        ROUTE_ANDROID,
        ROUTE_RELEASE_BUILD_LOGIC,
    }
    run_rust_coverage = route in {
        ROUTE_FULL,
        ROUTE_RUST_NATIVE,
        ROUTE_RELEASE_BUILD_LOGIC,
    }
    return {
        "route": route,
        "docs_only": str(route == ROUTE_DOCUMENTATION).lower(),
        "run_full_ci": str(run_full_ci).lower(),
        "run_android_ci": str(route == ROUTE_ANDROID).lower(),
        "run_rust_native_ci": str(route == ROUTE_RUST_NATIVE).lower(),
        "run_kotlin_coverage": str(run_kotlin_coverage).lower(),
        "run_rust_coverage": str(run_rust_coverage).lower(),
        "run_fixtures_ci": str(route == ROUTE_FIXTURES).lower(),
        "run_workflow_ci": str(
            route == ROUTE_WORKFLOW
            or any(is_workflow_configuration_path(path) for path in paths)
        ).lower(),
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Resolve conservative CI change-routing outputs."
    )
    parser.add_argument("--paths-file", type=Path, required=True)
    parser.add_argument("--github-output", type=Path, required=True)
    args = parser.parse_args()

    paths = [
        line.strip()
        for line in args.paths_file.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    outputs = routing_outputs(paths)
    args.github_output.write_text(
        "".join(f"{name}={value}\n" for name, value in outputs.items()),
        encoding="utf-8",
    )
    print(f"CI change routing: {outputs['route']} ({len(paths)} files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
