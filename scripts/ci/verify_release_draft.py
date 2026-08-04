#!/usr/bin/env python3
"""Accept only an exact, still-draft release as a resumable publication."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path


class DraftError(ValueError):
    pass


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory(directory: Path) -> dict[str, str]:
    if not directory.is_dir():
        raise DraftError(f"release asset directory is missing: {directory}")
    result = {}
    for path in directory.iterdir():
        if path.is_symlink() or not path.is_file():
            raise DraftError(f"release asset is not a regular file: {path}")
        result[path.name] = digest(path)
    return result


def normalize_notes(value: str) -> str:
    return value.replace("\r\n", "\n").rstrip("\n") + "\n"


def verify(
    metadata: dict, expected_dir: Path, actual_dir: Path, notes_path: Path, tag: str
) -> None:
    if metadata.get("tagName") != tag or metadata.get("isDraft") is not True:
        raise DraftError("existing release is not the exact resumable draft")
    if not isinstance(metadata.get("body"), str) or normalize_notes(
        metadata["body"]
    ) != normalize_notes(notes_path.read_text(encoding="utf-8")):
        raise DraftError("existing draft release notes differ")
    expected = inventory(expected_dir)
    actual = inventory(actual_dir)
    if expected != actual:
        raise DraftError(
            f"existing draft asset inventory differs; missing={sorted(set(expected) - set(actual))}, "
            f"extra={sorted(set(actual) - set(expected))}, "
            f"changed={sorted(name for name in set(expected) & set(actual) if expected[name] != actual[name])}"
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--metadata", required=True, type=Path)
    parser.add_argument("--expected-directory", required=True, type=Path)
    parser.add_argument("--actual-directory", required=True, type=Path)
    parser.add_argument("--release-notes", required=True, type=Path)
    parser.add_argument("--tag", required=True)
    args = parser.parse_args()
    try:
        metadata = json.loads(args.metadata.read_text(encoding="utf-8"))
        verify(
            metadata,
            args.expected_directory,
            args.actual_directory,
            args.release_notes,
            args.tag,
        )
    except (OSError, json.JSONDecodeError, DraftError) as error:
        print(f"release draft verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
