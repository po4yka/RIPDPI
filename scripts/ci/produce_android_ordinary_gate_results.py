#!/usr/bin/env python3
"""Emit fail-closed local results for Android ordinary release gates.

There is intentionally no pluggable collector. PASS remains impossible until a
checked-in, source-owned verifier derives observations from raw packet/route
artifacts. This prevents hand-authored counters or public hashes from becoming
release evidence.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
RESULTS_VERSION = "dns_ipv6_killswitch_results_v1"
APPLIES_TO = "android-client-release"
SHA1_RE = re.compile(r"[0-9a-f]{40}")
SOURCE_OWNED_VERIFIER_AVAILABLE = False
UNAVAILABLE_CODE = "SOURCE_OWNED_VERIFIER_UNAVAILABLE"
UNAVAILABLE_REASON = (
    "checked-in raw-artifact verifier is not implemented; ordinary PASS is forbidden"
)

ORDINARY_GATE_IDS = (
    "ipv4only-no-ipv6-dns-address-route",
    "ipv4only-no-direct-ipv6",
    "ipv4only-blocked-ipv6-only-connect",
    "ipv4only-empty-or-blocked-aaaa",
    "dualstack-default-route-through-tunnel",
    "dualstack-aaaa-through-tunnel",
    "killswitch-forced-disconnect",
    "killswitch-core-crash",
    "killswitch-wifi-lte-switch",
    "killswitch-sleep-wake",
    "killswitch-android-always-on-block",
)


class EvidenceError(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


def canonical_json_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode()


def write_canonical_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(canonical_json_bytes(value))
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass


def current_source_sha(root: Path = ROOT) -> str:
    status = subprocess.run(
        ["git", "status", "--porcelain", "--untracked-files=all"],
        cwd=root,
        capture_output=True,
        check=False,
        text=True,
    )
    if status.returncode != 0 or status.stdout:
        raise EvidenceError(
            "SOURCE_DIRTY", "ordinary evidence requires a clean source checkout"
        )
    return current_head_sha(root)


def current_head_sha(root: Path = ROOT) -> str:
    revision = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=root,
        capture_output=True,
        check=False,
        text=True,
    )
    source_sha = revision.stdout.strip()
    if revision.returncode != 0 or SHA1_RE.fullmatch(source_sha) is None:
        raise EvidenceError(
            "SOURCE_INVALID", "current source SHA could not be resolved"
        )
    return source_sha


def all_failure_results(
    source_sha: str,
    *,
    code: str = UNAVAILABLE_CODE,
    reason: str = UNAVAILABLE_REASON,
) -> dict[str, Any]:
    failure = {"reason": f"{code}: {reason}", "state": "FAIL"}
    return {
        "appliesTo": APPLIES_TO,
        "gateResults": {gate_id: dict(failure) for gate_id in ORDINARY_GATE_IDS},
        "sourceSha": source_sha,
        "version": RESULTS_VERSION,
    }


def validate_pass_results(*_args: Any, **_kwargs: Any) -> None:
    raise EvidenceError(UNAVAILABLE_CODE, UNAVAILABLE_REASON)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)

    try:
        source_sha = current_head_sha()
    except Exception as error:  # noqa: BLE001 - stale PASS must not survive
        args.output.unlink(missing_ok=True)
        print(
            f"Android ordinary producer failed before source binding: {error}",
            file=sys.stderr,
        )
        return 2

    write_canonical_json(
        args.output,
        all_failure_results(
            source_sha,
            code="SOURCE_VALIDATION_PENDING",
            reason="clean source validation has not completed",
        ),
    )
    try:
        validated_source_sha = current_source_sha()
        if validated_source_sha != source_sha:
            raise EvidenceError(
                "SOURCE_CHANGED", "source HEAD changed during clean validation"
            )
        results = all_failure_results(source_sha)
    except Exception as error:  # noqa: BLE001 - finalize every provenance failure
        if isinstance(error, EvidenceError):
            results = all_failure_results(
                source_sha, code=error.code, reason=error.message
            )
        else:
            results = all_failure_results(
                source_sha,
                code="SOURCE_VALIDATION_FAILED",
                reason=f"unexpected source validation failure ({type(error).__name__})",
            )
    write_canonical_json(args.output, results)
    print("Android ordinary release evidence is NO-SHIP:", file=sys.stderr)
    for gate_id in ORDINARY_GATE_IDS:
        print(
            f"  - {gate_id}: {results['gateResults'][gate_id]['reason']}",
            file=sys.stderr,
        )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
