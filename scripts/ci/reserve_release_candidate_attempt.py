#!/usr/bin/env python3
"""Reserve a durable tag ref for one release-candidate workflow attempt."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import time
from pathlib import Path


TAG = re.compile(r"^v\d+\.\d+\.\d+$")
SHA = re.compile(r"^[0-9a-f]{40}$")


def _gh(arguments: list[str], *, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(["gh", "api", *arguments], text=True, capture_output=True, check=False)
    if check and result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "GitHub API request failed")
    return result


def reserve(repository: str, release_tag: str, source_sha: str, run_id: int) -> list[dict[str, str]]:
    if re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) is None:
        raise ValueError("invalid GitHub repository")
    if TAG.fullmatch(release_tag) is None or SHA.fullmatch(source_sha) is None or run_id < 1:
        raise ValueError("invalid candidate attempt identity")
    short_ref = f"tags/release-candidates/{release_tag}/run-{run_id}"
    full_ref = f"refs/{short_ref}"
    existing = _gh([f"repos/{repository}/git/ref/{short_ref}"], check=False)
    if existing.returncode == 0:
        payload = json.loads(existing.stdout)
        if payload.get("ref") != full_ref or payload.get("object", {}).get("sha") != source_sha:
            raise RuntimeError("candidate attempt ref exists with different identity")
    else:
        _gh([
            "--method", "POST", f"repos/{repository}/git/refs",
            "-f", f"ref={full_ref}", "-f", f"sha={source_sha}",
        ])
    for retry in range(3):
        pages = json.loads(_gh([
            "--paginate", "--slurp",
            f"repos/{repository}/git/matching-refs/tags/release-candidates/{release_tag}/run-",
        ]).stdout)
        attempts = [
            {"ref": item["ref"], "sha": item["object"]["sha"]}
            for page in pages
            for item in page
        ]
        if {"ref": full_ref, "sha": source_sha} in attempts:
            return attempts
        if retry < 2:
            time.sleep(1)
    raise RuntimeError("reserved candidate attempt ref is absent from durable attempt set")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--run-id", required=True, type=int)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        attempts = reserve(args.repository, args.release_tag, args.source_sha, args.run_id)
    except (ValueError, RuntimeError, json.JSONDecodeError, KeyError, TypeError) as error:
        parser.error(str(error))
    args.output.write_text(json.dumps(attempts, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
