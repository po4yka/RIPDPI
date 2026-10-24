#!/usr/bin/env python3
"""Print `-p <package>` arguments for the workspace packages that own the given Rust files.

Pre-commit clippy uses this to lint only the crates a commit touches; CI's
workspace-wide clippy still covers dependents. Paths may be relative to the
current directory or to the repository root. Files outside every workspace
package produce no output.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
WORKSPACE_MANIFEST = REPO_ROOT / "native" / "rust" / "Cargo.toml"


def package_roots() -> list[tuple[Path, str]]:
    metadata = subprocess.run(
        ["cargo", "metadata", "--no-deps", "--locked", "--format-version", "1",
         "--manifest-path", str(WORKSPACE_MANIFEST)],
        check=True, capture_output=True, text=True,
    ).stdout
    roots = [(Path(package["manifest_path"]).parent.resolve(), package["name"])
             for package in json.loads(metadata)["packages"]]
    # Deepest root first, so a nested package wins over its parent.
    return sorted(roots, key=lambda root: len(root[0].parts), reverse=True)


def owning_packages(paths: list[str], roots: list[tuple[Path, str]]) -> list[str]:
    owners: set[str] = set()
    for raw in paths:
        candidate = Path(raw)
        if not candidate.is_absolute() and not candidate.exists():
            candidate = REPO_ROOT / raw
        resolved = candidate.resolve()
        owner = next((name for root, name in roots if resolved.is_relative_to(root)), None)
        if owner is not None:
            owners.add(owner)
    return sorted(owners)


def main(arguments: list[str]) -> int:
    packages = owning_packages(arguments, package_roots())
    print(" ".join(f"-p {package}" for package in packages))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
