#!/usr/bin/env python3

from __future__ import annotations

import argparse
import difflib
import json
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
RUST_ROOT = REPO_ROOT / "native" / "rust"
CRATES_ROOT = RUST_ROOT / "crates"
WORKSPACE_MANIFEST = RUST_ROOT / "Cargo.toml"
HIGH_INDEGREE_THRESHOLD = 10
EXPLICIT_SNAPSHOT_CRATES = frozenset({"ripdpi-runtime-api", "ripdpi-ws-transport-port"})
PUBLIC_API_ARGS = ("-sss",)
PUBLIC_API_NORMALIZATIONS = {
    "core::io::error::ErrorKind": "std::io::error::ErrorKind",
    "core::io::error::Error": "std::io::error::Error",
    "core::io::error::Result": "std::io::error::Result",
}
LINUX_ONLY_SNAPSHOT_CRATES = frozenset({"ripdpi-runtime-platform"})


@dataclass(frozen=True)
class WorkspaceCrate:
    name: str
    manifest_path: Path


@dataclass(frozen=True)
class SnapshotTarget:
    crate: WorkspaceCrate
    indegree: int
    reason: str


@dataclass(frozen=True)
class SnapshotViolation:
    crate_name: str
    message: str


def load_cargo_metadata(manifest_path: Path = WORKSPACE_MANIFEST) -> dict[str, Any]:
    result = subprocess.run(
        ["cargo", "metadata", "--manifest-path", str(manifest_path), "--format-version", "1", "--no-deps"],
        check=True,
        capture_output=True,
        text=True,
    )
    return json.loads(result.stdout)


def workspace_crates_from_metadata(metadata: dict[str, Any], crates_root: Path = CRATES_ROOT) -> dict[str, WorkspaceCrate]:
    workspace_member_ids = set(metadata["workspace_members"])
    crates: dict[str, WorkspaceCrate] = {}

    for package in metadata["packages"]:
        if package["id"] not in workspace_member_ids:
            continue

        manifest_path = Path(package["manifest_path"])
        if not manifest_path.is_relative_to(crates_root):
            continue

        crates[package["name"]] = WorkspaceCrate(name=package["name"], manifest_path=manifest_path)

    return crates


def internal_production_reverse_dependencies(
    metadata: dict[str, Any],
    crates: dict[str, WorkspaceCrate],
) -> dict[str, set[str]]:
    workspace_crate_names = set(crates)
    reverse_dependencies: dict[str, set[str]] = {crate_name: set() for crate_name in workspace_crate_names}

    for package in metadata["packages"]:
        package_name = package["name"]
        if package_name not in workspace_crate_names:
            continue

        for dependency in package["dependencies"]:
            if dependency["kind"] is not None:
                continue
            dependency_name = dependency["name"]
            if dependency_name in workspace_crate_names:
                reverse_dependencies[dependency_name].add(package_name)

    return reverse_dependencies


def snapshot_targets(
    metadata: dict[str, Any],
    threshold: int = HIGH_INDEGREE_THRESHOLD,
    explicit_snapshot_crates: frozenset[str] = EXPLICIT_SNAPSHOT_CRATES,
    crates_root: Path = CRATES_ROOT,
) -> list[SnapshotTarget]:
    crates = workspace_crates_from_metadata(metadata, crates_root=crates_root)
    reverse_dependencies = internal_production_reverse_dependencies(metadata, crates)
    targets: list[SnapshotTarget] = []

    for crate_name, crate in crates.items():
        indegree = len(reverse_dependencies[crate_name])
        is_high_indegree = indegree >= threshold
        is_explicit = crate_name in explicit_snapshot_crates
        if not is_high_indegree and not is_explicit:
            continue

        reasons: list[str] = []
        if is_high_indegree:
            reasons.append(f"indegree {indegree} >= {threshold}")
        if is_explicit:
            reasons.append("explicit API boundary")
        targets.append(SnapshotTarget(crate=crate, indegree=indegree, reason=", ".join(reasons)))

    return sorted(targets, key=lambda target: (-target.indegree, target.crate.name))


def snapshot_path(crate: WorkspaceCrate) -> Path:
    return crate.manifest_path.parent / "api-snapshot.txt"


def should_check_snapshot_on_host(target: SnapshotTarget, platform: str = sys.platform) -> bool:
    """Keep cfg-gated API snapshots owned by their canonical CI host."""
    return target.crate.name not in LINUX_ONLY_SNAPSHOT_CRATES or platform.startswith("linux")


def cargo_public_api(crate: WorkspaceCrate) -> str:
    manifest_path = crate.manifest_path.relative_to(RUST_ROOT)
    result = subprocess.run(
        ["cargo", "public-api", *PUBLIC_API_ARGS, "--manifest-path", manifest_path.as_posix()],
        cwd=RUST_ROOT,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        print(result.stdout, end="")
        print(result.stderr, end="", file=sys.stderr)
        raise RuntimeError(f"cargo public-api failed for {crate.name}")
    return normalize_public_api(result.stdout)


def normalize_public_api(public_api: str) -> str:
    normalized = public_api
    for current, canonical in PUBLIC_API_NORMALIZATIONS.items():
        normalized = normalized.replace(current, canonical)
    return normalized


def ensure_public_api_tool_available() -> None:
    if shutil.which("cargo-public-api") is None:
        print(
            "error: cargo-public-api is required for Rust API snapshot checks.\n"
            "Install it with: cargo install cargo-public-api",
            file=sys.stderr,
        )
        sys.exit(2)


def check_snapshot(target: SnapshotTarget, update: bool) -> list[SnapshotViolation]:
    path = snapshot_path(target.crate)
    current_api = cargo_public_api(target.crate)

    if update:
        path.write_text(current_api, encoding="utf-8")
        return []

    if not path.exists():
        return [
            SnapshotViolation(
                crate_name=target.crate.name,
                message=(
                    f"missing {path.relative_to(REPO_ROOT).as_posix()}; generate it with "
                    "`python3 scripts/ci/check_rust_api_snapshots.py --update`"
                ),
            )
        ]

    expected_api = path.read_text(encoding="utf-8")
    if expected_api == current_api:
        return []

    diff = "".join(
        difflib.unified_diff(
            expected_api.splitlines(keepends=True),
            current_api.splitlines(keepends=True),
            fromfile=path.relative_to(REPO_ROOT).as_posix(),
            tofile=f"current/{target.crate.name}",
        )
    )
    return [
        SnapshotViolation(
            crate_name=target.crate.name,
            message=(
                "public API snapshot drifted; refresh intentionally with "
                "`python3 scripts/ci/check_rust_api_snapshots.py --update`\n"
                f"{diff}"
            ),
        )
    ]


def check_snapshots(update: bool) -> int:
    ensure_public_api_tool_available()
    targets = snapshot_targets(load_cargo_metadata())
    violations: list[SnapshotViolation] = []

    action = "Updating" if update else "Checking"
    print(
        f"{action} Rust API snapshots for {len(targets)} crates "
        f"(high-indegree threshold: {HIGH_INDEGREE_THRESHOLD})",
        flush=True,
    )
    for target in targets:
        if not should_check_snapshot_on_host(target):
            print(f"  {target.crate.name}: skipped on {sys.platform}; canonical snapshot is Linux-owned", flush=True)
            continue
        relative_snapshot_path = snapshot_path(target.crate).relative_to(REPO_ROOT)
        print(f"  {target.crate.name}: {target.reason} -> {relative_snapshot_path.as_posix()}", flush=True)
        try:
            violations.extend(check_snapshot(target, update))
        except RuntimeError as error:
            violations.append(SnapshotViolation(crate_name=target.crate.name, message=str(error)))

    if not violations:
        if update:
            print("Rust API snapshots updated.")
        else:
            print("Rust API snapshots match.")
        return 0

    print("", file=sys.stderr)
    print("error: Rust API snapshot check failed:", file=sys.stderr)
    for violation in violations:
        print(f"\n[{violation.crate_name}]\n{violation.message}", file=sys.stderr)
    return 1


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check checked-in Rust public API snapshots.")
    parser.add_argument(
        "--update",
        action="store_true",
        help="regenerate api-snapshot.txt files for all required snapshot targets",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    return check_snapshots(update=args.update)


if __name__ == "__main__":
    raise SystemExit(main())
