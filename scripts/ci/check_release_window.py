#!/usr/bin/env python3
"""Enforce a short, release-fix-only window before candidate signing."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONTRACT = ROOT / "quality/release-gates/release-contract.json"
TAG = re.compile(r"^v\d+\.\d+\.\d+$")


class WindowError(ValueError):
    pass


def _git(repo: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=repo, text=True, capture_output=True, check=False
    )
    if result.returncode != 0:
        raise WindowError(result.stderr.strip() or f"git {' '.join(args)} failed")
    return result.stdout.strip()


def _window(contract_path: Path) -> dict[str, Any]:
    contract = json.loads(contract_path.read_text(encoding="utf-8"))
    window = contract.get("releaseWindow")
    if not isinstance(window, dict):
        raise WindowError("release contract has no releaseWindow")
    required = {
        "startShaVariable",
        "startedAtVariable",
        "recordDirectory",
        "maxAgeHours",
        "maxCommits",
        "maxCandidateRuns",
        "allowedSubjectPatterns",
    }
    if set(window) != required:
        raise WindowError("releaseWindow fields do not match the supported contract")
    for name in ("maxAgeHours", "maxCommits", "maxCandidateRuns"):
        value = window[name]
        if not isinstance(value, int) or isinstance(value, bool) or value < 1:
            raise WindowError(f"releaseWindow.{name} must be a positive integer")
    patterns = window["allowedSubjectPatterns"]
    if not isinstance(patterns, list) or not patterns or not all(isinstance(item, str) for item in patterns):
        raise WindowError("releaseWindow.allowedSubjectPatterns must be a string list")
    try:
        for pattern in patterns:
            re.compile(pattern)
    except re.error as error:
        raise WindowError("releaseWindow contains an invalid subject pattern") from error
    return window


def _cut_record(
    repo: Path, window: dict[str, Any], release_tag: str
) -> tuple[str, datetime]:
    record = Path(window["recordDirectory"]) / f"{release_tag}.json"
    record_path = repo / record
    if not record_path.is_file() or record_path.is_symlink():
        raise WindowError(f"checked-in release window record is missing: {record}")
    try:
        payload = json.loads(record_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as error:
        raise WindowError("release window record is malformed") from error
    if not isinstance(payload, dict) or set(payload) != {"version", "releaseTag", "startedAt"}:
        raise WindowError("release window record fields are invalid")
    if payload["version"] != "ripdpi_release_window_cut_v1" or payload["releaseTag"] != release_tag:
        raise WindowError("release window record identity does not match candidate")
    try:
        record_started = datetime.fromisoformat(str(payload["startedAt"]).replace("Z", "+00:00"))
    except ValueError as error:
        raise WindowError("release window record startedAt is malformed") from error
    if record_started.tzinfo is None:
        raise WindowError("release window record startedAt must include a timezone")
    additions = _git(repo, "log", "--diff-filter=A", "--format=%H", "--", str(record)).splitlines()
    if len(additions) != 1:
        raise WindowError("release window record must have exactly one introduction commit")
    cut_sha = additions[0]
    historical = _git(repo, "show", f"{cut_sha}:{record}")
    if historical + "\n" != record_path.read_text(encoding="utf-8"):
        raise WindowError("release window record changed after its introduction")
    return cut_sha, record_started.astimezone(UTC)


def evaluate_release_window(
    repo: Path,
    contract_path: Path,
    release_tag: str,
    start_sha: str,
    candidate_sha: str,
    started_at: datetime,
    now: datetime,
    runs: list[dict[str, Any]],
) -> dict[str, Any]:
    if TAG.fullmatch(release_tag) is None:
        raise WindowError(f"invalid release tag: {release_tag}")
    window = _window(contract_path)
    title_prefix = f"Android release candidate {release_tag} @ "
    candidate_runs = [run for run in runs if run.get("displayTitle", "").startswith(title_prefix)]
    if len(candidate_runs) > window["maxCandidateRuns"]:
        raise WindowError(
            f"candidate run limit exceeded: {len(candidate_runs)} > {window['maxCandidateRuns']}"
        )
    if started_at.tzinfo is None or now.tzinfo is None:
        raise WindowError("timestamp must include a timezone")
    cut_sha, record_started = _cut_record(repo, window, release_tag)
    started = started_at.astimezone(UTC)
    if started != record_started:
        raise WindowError("release window timestamp does not match checked-in cut record")
    current = now.astimezone(UTC)
    age_hours = (current - started).total_seconds() / 3600
    if not candidate_runs and age_hours < 0:
        raise WindowError("release window starts in the future")
    if not candidate_runs and age_hours > window["maxAgeHours"]:
        raise WindowError(
            f"release window expired: {age_hours:.1f}h > {window['maxAgeHours']}h"
        )

    start = _git(repo, "rev-parse", "--verify", f"{start_sha}^{{commit}}")
    if start != cut_sha:
        raise WindowError("release window start does not match checked-in cut record")
    candidate = _git(repo, "rev-parse", "--verify", f"{candidate_sha}^{{commit}}")
    if subprocess.run(
        ["git", "merge-base", "--is-ancestor", start, candidate], cwd=repo, check=False
    ).returncode != 0:
        raise WindowError("release window start is not an ancestor of candidate SHA")
    subjects = _git(repo, "log", "--format=%s", f"{start}..{candidate}").splitlines()
    if len(subjects) > window["maxCommits"]:
        raise WindowError(
            f"release window commit limit exceeded: {len(subjects)} > {window['maxCommits']}"
        )
    allowed = [re.compile(pattern) for pattern in window["allowedSubjectPatterns"]]
    unsafe = [subject for subject in subjects if not any(pattern.fullmatch(subject) for pattern in allowed)]
    if unsafe:
        raise WindowError("commit is not release-window safe: " + "; ".join(unsafe))

    for run in candidate_runs:
        head_sha = run.get("headSha")
        created_at = run.get("createdAt")
        if not isinstance(head_sha, str) or not head_sha:
            raise WindowError("candidate run is missing headSha")
        if not isinstance(created_at, str):
            raise WindowError("candidate run is missing createdAt")
        try:
            run_created = datetime.fromisoformat(created_at.replace("Z", "+00:00"))
        except ValueError as error:
            raise WindowError("candidate run createdAt is malformed") from error
        if run_created.tzinfo is None:
            raise WindowError("candidate run createdAt must include a timezone")
        prior_sha = _git(repo, "rev-parse", "--verify", f"{head_sha}^{{commit}}")
        if subprocess.run(
            ["git", "merge-base", "--is-ancestor", start, prior_sha],
            cwd=repo,
            check=False,
        ).returncode != 0:
            raise WindowError("release window start is not an ancestor of prior candidate")
    return {
        "version": "ripdpi_release_window_v1",
        "releaseTag": release_tag,
        "startSha": start,
        "candidateSha": candidate,
        "startedAt": started.isoformat().replace("+00:00", "Z"),
        "ageHours": round(age_hours, 3),
        "commitCount": len(subjects),
        "candidateRunCount": len(candidate_runs),
    }


def _timestamp(value: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise argparse.ArgumentTypeError("expected an ISO-8601 timestamp") from error
    if parsed.tzinfo is None:
        raise argparse.ArgumentTypeError("timestamp must include a timezone")
    return parsed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=ROOT)
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--start-sha", required=True)
    parser.add_argument("--candidate-sha", required=True)
    parser.add_argument("--started-at", required=True, type=_timestamp)
    parser.add_argument("--now", type=_timestamp, default=datetime.now(UTC))
    parser.add_argument("--runs-json", required=True, type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        runs = json.loads(args.runs_json.read_text(encoding="utf-8"))
        if not isinstance(runs, list) or not all(isinstance(run, dict) for run in runs):
            raise WindowError("runs JSON must be an array of objects")
        report = evaluate_release_window(
            args.repo.resolve(), args.contract.resolve(), args.release_tag,
            args.start_sha, args.candidate_sha, args.started_at, args.now, runs,
        )
    except (WindowError, json.JSONDecodeError, OSError) as error:
        parser.error(str(error))
    output = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.report:
        args.report.write_text(output, encoding="utf-8")
    print(output, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
