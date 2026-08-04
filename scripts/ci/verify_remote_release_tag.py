#!/usr/bin/env python3
"""Verify that the current remote tag still peels to the approved source SHA."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections.abc import Callable
from urllib.parse import quote


SHA_RE = re.compile(r"[0-9a-f]{40}\Z")
TAG_RE = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+\Z")


class TagError(ValueError):
    pass


def peel_tag(tag: str, expected_sha: str, fetch: Callable[[str], dict]) -> str:
    if TAG_RE.fullmatch(tag) is None or SHA_RE.fullmatch(expected_sha) is None:
        raise TagError("tag or expected source SHA is malformed")
    reference = fetch(f"git/ref/tags/{quote(tag, safe='')}")
    obj = reference.get("object")
    visited: set[str] = set()
    for _ in range(8):
        if not isinstance(obj, dict) or set(obj) < {"type", "sha"}:
            raise TagError("remote tag object is malformed")
        object_type, sha = obj["type"], obj["sha"]
        if not isinstance(sha, str) or SHA_RE.fullmatch(sha) is None or sha in visited:
            raise TagError("remote tag chain is malformed or cyclic")
        visited.add(sha)
        if object_type == "commit":
            if sha != expected_sha:
                raise TagError(f"remote tag peels to {sha}, expected {expected_sha}")
            return sha
        if object_type != "tag":
            raise TagError(
                f"remote tag points to unsupported object type: {object_type}"
            )
        obj = fetch(f"git/tags/{sha}").get("object")
    raise TagError("remote tag chain is too deep")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--gh", default="gh")
    args = parser.parse_args()

    def fetch(path: str) -> dict:
        result = subprocess.run(
            [args.gh, "api", f"repos/{args.repository}/{path}"],
            capture_output=True,
            text=True,
            check=False,
        )
        if result.returncode != 0:
            raise TagError(
                f"GitHub tag lookup failed: {result.stderr.strip() or result.returncode}"
            )
        value = json.loads(result.stdout)
        if not isinstance(value, dict):
            raise TagError("GitHub tag response is not an object")
        return value

    try:
        peel_tag(args.tag, args.expected_sha, fetch)
    except (OSError, json.JSONDecodeError, TagError) as error:
        print(f"remote release tag verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
