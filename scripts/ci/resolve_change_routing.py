#!/usr/bin/env python3
"""Classify a CI change set conservatively for workflow routing."""

from __future__ import annotations

import argparse
from pathlib import Path


def is_documentation_path(path: str) -> bool:
    return path.startswith("docs/") or (path.startswith("README") and path.endswith(".md") and "/" not in path)


def is_documentation_only(paths: list[str]) -> bool:
    return bool(paths) and all(is_documentation_path(path) for path in paths)


def main() -> int:
    parser = argparse.ArgumentParser(description="Resolve conservative CI change-routing outputs.")
    parser.add_argument("--paths-file", type=Path, required=True)
    parser.add_argument("--github-output", type=Path, required=True)
    args = parser.parse_args()

    paths = [line.strip() for line in args.paths_file.read_text(encoding="utf-8").splitlines() if line.strip()]
    docs_only = is_documentation_only(paths)
    args.github_output.write_text(
        f"docs_only={'true' if docs_only else 'false'}\n"
        f"run_full_ci={'false' if docs_only else 'true'}\n",
        encoding="utf-8",
    )
    print(f"CI change routing: {'documentation-only' if docs_only else 'full'} ({len(paths)} files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
