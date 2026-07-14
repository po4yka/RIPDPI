#!/usr/bin/env python3
"""Block agent edits that weaken committed quality baselines."""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path, PurePosixPath
from typing import Any

from extract_hook_paths import extract_paths


PROTECTED_PATHS = {
    "config/static/architecture-health-baseline.json",
    "config/static/file-loc-baseline.json",
    "scripts/ci/module-deps-baseline.json",
    "scripts/ci/rust-allow-baseline.json",
}
PROTECTED_NAMES = {"detekt-baseline.xml", "lint-baseline.xml"}


REPO_ROOT = Path(__file__).resolve().parents[2]


def normalized_repo_path(path: str, cwd: str) -> str:
    candidate = Path(os.path.normpath(path))
    absolute = candidate if candidate.is_absolute() else Path(cwd) / candidate
    try:
        candidate = absolute.resolve().relative_to(REPO_ROOT)
    except ValueError:
        candidate = absolute
    return PurePosixPath(candidate).as_posix().removeprefix("./")


def protected_path(payload: Any) -> str | None:
    cwd = payload.get("cwd") if isinstance(payload, dict) else None
    root = cwd if isinstance(cwd, str) and cwd else str(REPO_ROOT)
    for raw_path in extract_paths(payload):
        path = normalized_repo_path(raw_path, root)
        if path in PROTECTED_PATHS or PurePosixPath(path).name in PROTECTED_NAMES:
            return path
    return None


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, OSError):
        return 0

    path = protected_path(payload)
    if path is None:
        return 0

    reason = f"Blocked edit to quality baseline {path}. Fix the underlying violation instead of extending the baseline."
    json.dump(
        {
            "decision": "block",
            "reason": reason,
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "deny",
                "permissionDecisionReason": reason,
            },
        },
        sys.stdout,
    )
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
