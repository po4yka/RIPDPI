#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
WORKSPACE_MANIFEST = REPO_ROOT / "native/rust/Cargo.toml"

RUNTIME_API_FORBIDDEN_PROD_DEPS = {
    "ripdpi-capabilities",
    "ripdpi-cloudflare-origin",
    "ripdpi-desync-runtime",
    "ripdpi-dns-resolver",
    "ripdpi-hysteria2",
    "ripdpi-io-uring",
    "ripdpi-ipfrag",
    "ripdpi-masque",
    "ripdpi-native-protect",
    "ripdpi-naiveproxy",
    "ripdpi-privileged-ops",
    "ripdpi-proxy-runtime",
    "ripdpi-relay-core",
    "ripdpi-relay-mux",
    "ripdpi-root-helper-protocol",
    "ripdpi-runtime-platform",
    "ripdpi-session",
    "ripdpi-shadowtls",
    "ripdpi-tuic",
    "ripdpi-vless",
    "ripdpi-ws-bootstrap",
    "ripdpi-ws-tunnel",
    "ripdpi-xhttp",
}

RUNTIME_PLATFORM_ALLOWED_PROD_DEPS = {
    "ripdpi-capabilities",
    "ripdpi-config",
    "ripdpi-desync",
    "ripdpi-io-uring",
    "ripdpi-ipfrag",
    "ripdpi-native-protect",
    "ripdpi-privileged-ops",
    "ripdpi-root-helper-protocol",
}

PROXY_RUNTIME_REQUIRED_PROD_DEPS = {
    "ripdpi-runtime-api",
    "ripdpi-proxy-runtime-adapter",
}

PROXY_RUNTIME_FORBIDDEN_PROD_DEPS = {
    "ripdpi-runtime-platform",
}

PRODUCTION_CALLERS = {
    "ripdpi-android",
    "ripdpi-cli",
    "ripdpi-monitor-engine",
}


@dataclass(frozen=True)
class Violation:
    package: str
    dependency: str
    message: str


@dataclass(frozen=True)
class PackageDeps:
    prod: set[str]
    dev: set[str]


def load_metadata(metadata_file: Path | None) -> dict[str, Any]:
    if metadata_file is not None:
        return json.loads(metadata_file.read_text(encoding="utf-8"))

    output = subprocess.check_output(
        [
            "cargo",
            "metadata",
            "--locked",
            "--manifest-path",
            str(WORKSPACE_MANIFEST),
            "--format-version",
            "1",
        ],
        text=True,
    )
    return json.loads(output)


def workspace_package_names(metadata: dict[str, Any]) -> set[str]:
    workspace_ids = set(metadata["workspace_members"])
    return {package["name"] for package in metadata["packages"] if package["id"] in workspace_ids}


def workspace_dependency_map(metadata: dict[str, Any]) -> dict[str, PackageDeps]:
    workspace_ids = set(metadata["workspace_members"])
    deps_by_package: dict[str, PackageDeps] = {}

    for package in metadata["packages"]:
        if package["id"] not in workspace_ids:
            continue

        prod: set[str] = set()
        dev: set[str] = set()
        for dependency in package.get("dependencies", []):
            dependency_name = dependency["name"]
            if dependency.get("kind") == "dev":
                dev.add(dependency_name)
            else:
                prod.add(dependency_name)

        deps_by_package[package["name"]] = PackageDeps(prod=prod, dev=dev)

    return deps_by_package


def check_no_old_runtime_package(workspace_names: set[str], deps_by_package: dict[str, PackageDeps]) -> list[Violation]:
    violations: list[Violation] = []
    if "ripdpi-runtime" in workspace_names:
        violations.append(
            Violation(
                package="workspace",
                dependency="ripdpi-runtime",
                message="old broad runtime crate must not be a workspace package",
            )
        )

    for package_name, deps in sorted(deps_by_package.items()):
        if "ripdpi-runtime" in deps.prod:
            violations.append(
                Violation(
                    package=package_name,
                    dependency="ripdpi-runtime",
                    message="production crates must use split runtime crates, not the broad runtime facade",
                )
            )

    return violations


def check_forbidden_deps(
    deps_by_package: dict[str, PackageDeps],
    package_name: str,
    forbidden_deps: set[str],
    message: str,
) -> list[Violation]:
    deps = deps_by_package.get(package_name)
    if deps is None:
        return [
            Violation(
                package=package_name,
                dependency="<missing>",
                message="expected split runtime package is missing from the workspace",
            )
        ]

    return [
        Violation(package=package_name, dependency=dep, message=message)
        for dep in sorted(deps.prod & forbidden_deps)
    ]


def check_platform_allowlist(deps_by_package: dict[str, PackageDeps]) -> list[Violation]:
    deps = deps_by_package.get("ripdpi-runtime-platform")
    if deps is None:
        return [
            Violation(
                package="ripdpi-runtime-platform",
                dependency="<missing>",
                message="expected split runtime package is missing from the workspace",
            )
        ]

    unexpected = {
        dep
        for dep in deps.prod
        if dep.startswith("ripdpi-") and dep not in RUNTIME_PLATFORM_ALLOWED_PROD_DEPS
    }
    return [
        Violation(
            package="ripdpi-runtime-platform",
            dependency=dep,
            message="platform adapter crate may only depend on approved platform/config/desync crates",
        )
        for dep in sorted(unexpected)
    ]


def check_proxy_runtime_required_deps(deps_by_package: dict[str, PackageDeps]) -> list[Violation]:
    deps = deps_by_package.get("ripdpi-proxy-runtime")
    if deps is None:
        return [
            Violation(
                package="ripdpi-proxy-runtime",
                dependency="<missing>",
                message="expected proxy runtime package is missing from the workspace",
            )
        ]

    missing = PROXY_RUNTIME_REQUIRED_PROD_DEPS - deps.prod
    return [
        Violation(
            package="ripdpi-proxy-runtime",
            dependency=dep,
            message="proxy runtime orchestrator must depend on the split runtime API and proxy adapter crates",
        )
        for dep in sorted(missing)
    ]


def collect_violations(metadata: dict[str, Any]) -> list[Violation]:
    workspace_names = workspace_package_names(metadata)
    deps_by_package = workspace_dependency_map(metadata)
    violations: list[Violation] = []

    violations.extend(check_no_old_runtime_package(workspace_names, deps_by_package))
    violations.extend(
        check_forbidden_deps(
            deps_by_package,
            "ripdpi-runtime-api",
            RUNTIME_API_FORBIDDEN_PROD_DEPS,
            "runtime API crate must stay contract-only and avoid platform, DNS, WS tunnel, and proxy internals",
        )
    )
    violations.extend(check_platform_allowlist(deps_by_package))
    violations.extend(check_proxy_runtime_required_deps(deps_by_package))
    violations.extend(
        check_forbidden_deps(
            deps_by_package,
            "ripdpi-proxy-runtime",
            PROXY_RUNTIME_FORBIDDEN_PROD_DEPS,
            "proxy runtime must reach platform operations through ripdpi-proxy-runtime-adapter",
        )
    )

    for caller in sorted(PRODUCTION_CALLERS):
        violations.extend(
            check_forbidden_deps(
                deps_by_package,
                caller,
                {"ripdpi-runtime"},
                "production caller must use split runtime crates directly",
            )
        )

    return violations


def format_summary(violations: list[Violation]) -> str:
    lines = ["Runtime crate boundary guard", f"Violations: {len(violations)}"]
    for violation in violations:
        lines.append(f"  - {violation.package} -> {violation.dependency}: {violation.message}")
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Verify split runtime crate dependency boundaries.")
    parser.add_argument(
        "--metadata-file",
        type=Path,
        default=None,
        help="Read locked Cargo metadata JSON from a file instead of invoking cargo metadata --locked.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    violations = collect_violations(load_metadata(args.metadata_file))
    print(format_summary(violations))
    return 1 if violations else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"Runtime crate boundary guard failed: {exc}", file=sys.stderr)
        raise
