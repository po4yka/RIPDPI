#!/usr/bin/env python3
"""Extract candidate repository paths from Claude Code and Codex hook payloads."""

from __future__ import annotations

import json
import re
import sys
from collections.abc import Iterator
from typing import Any


PATCH_PATH = re.compile(r"^\*\*\* (?:Add|Delete|Update) File: (.+)$", re.MULTILINE)
PATH_KEYS = {"file", "file_path", "filename", "path"}


def _strings(value: Any) -> Iterator[str]:
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for nested in value.values():
            yield from _strings(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from _strings(nested)


def extract_paths(payload: Any) -> list[str]:
    """Return de-duplicated paths from structured inputs and apply_patch text."""
    candidates: list[str] = []

    def visit(value: Any) -> None:
        if isinstance(value, dict):
            for key, nested in value.items():
                if key.lower() in PATH_KEYS and isinstance(nested, str):
                    candidates.append(nested)
                visit(nested)
        elif isinstance(value, list):
            for nested in value:
                visit(nested)

    visit(payload)
    for value in _strings(payload):
        candidates.extend(PATCH_PATH.findall(value))

    return list(dict.fromkeys(path.strip() for path in candidates if path.strip()))


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except (json.JSONDecodeError, OSError):
        return 0
    for path in extract_paths(payload):
        print(path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
