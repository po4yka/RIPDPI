#!/usr/bin/env python3
"""Run the full secret-free local release preflight and emit a bounded receipt."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Callable

try:
    from scripts.ci.check_release_window import evaluate_release_window
except ModuleNotFoundError:  # Direct execution sets sys.path to scripts/ci.
    from check_release_window import evaluate_release_window


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONTRACT = ROOT / "quality/release-gates/release-contract.json"
CommandRunner = Callable[[list[str], Path], None]
RemoteTagChecker = Callable[[Path, str], None]


class PreflightError(RuntimeError):
    pass


def _git(repo: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=repo, text=True, capture_output=True, check=False
    )
    if result.returncode != 0:
        raise PreflightError(result.stderr.strip() or f"git {' '.join(args)} failed")
    return result.stdout.strip()


def _default_runner(command: list[str], cwd: Path) -> None:
    subprocess.run(command, cwd=cwd, check=True)


def _candidate_runs(repo: Path) -> list[dict[str, Any]]:
    repository = subprocess.run(
        ["gh", "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"],
        cwd=repo,
        text=True,
        capture_output=True,
        check=False,
    )
    if repository.returncode != 0 or not repository.stdout.strip():
        raise PreflightError("could not resolve GitHub repository for candidate-run audit")
    endpoint = (
        f"repos/{repository.stdout.strip()}/actions/workflows/"
        "release-candidate.yml/runs?event=workflow_dispatch&per_page=100"
    )
    result = subprocess.run(
        ["gh", "api", "--paginate", "--slurp", endpoint],
        cwd=repo,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise PreflightError("could not retrieve complete release-candidate run history")
    try:
        pages = json.loads(result.stdout)
        runs = [
            {
                "displayTitle": run["display_title"],
                "createdAt": run["created_at"],
                "headSha": run["head_sha"],
            }
            for page in pages
            for run in page["workflow_runs"]
        ]
    except (json.JSONDecodeError, KeyError, TypeError) as error:
        raise PreflightError("candidate-run history response is malformed") from error
    return runs


def _latest_stable_tag(repo: Path) -> str:
    tags = _git(repo, "tag", "--merged", "HEAD", "--list", "v[0-9]*", "--sort=-version:refname")
    for tag in tags.splitlines():
        if re.fullmatch(r"v\d+\.\d+\.\d+", tag):
            return tag
    raise PreflightError("no reachable stable release tag found")


def _ensure_remote_tag_absent(repo: Path, release_tag: str) -> None:
    result = subprocess.run(
        [
            "git", "ls-remote", "--exit-code", "--tags", "origin",
            f"refs/tags/{release_tag}", f"refs/tags/{release_tag}^{{}}",
        ],
        cwd=repo,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode == 0:
        raise PreflightError(f"target release tag already exists remotely: {release_tag}")
    if result.returncode != 2:
        raise PreflightError("could not verify remote target tag absence")


def _commands(release_tag: str, base_tag: str, source_sha: str) -> list[tuple[str, list[str]]]:
    python = sys.executable
    return [
        ("release-contract", [python, "scripts/ci/check_release_contract.py"]),
        (
            "release-contract-tests",
            [
                python,
                "-m",
                "unittest",
                "scripts.tests.test_release_contract",
                "scripts.tests.test_release_artifact_uploads",
                "scripts.tests.test_release_candidate_manifest",
                "scripts.tests.test_release_p0_contracts",
                "scripts.tests.test_release_p1_contracts",
                "scripts.tests.test_release_window",
                "scripts.tests.test_release_preflight",
                "scripts.tests.test_evidence_retention",
            ],
        ),
        ("architecture-health", [python, "scripts/ci/check_architecture_health.py", "--check"]),
        (
            "cargo-lock",
            ["cargo", "metadata", "--manifest-path", "native/rust/Cargo.toml", "--locked", "--no-deps"],
        ),
        (
            "release-identity",
            [
                "./gradlew",
                ":app:writeReleaseIdentityManifest",
                ":app:verifyReleaseVersion",
                f"-Pripdpi.releaseRefName={release_tag}",
            ],
        ),
        ("app-identity-review", [python, "scripts/ci/check_app_identity_review.py", "--report"]),
        ("owned-stack-tls", ["bash", "scripts/ci/check-owned-stack-tls-fingerprint.sh"]),
        (
            "secret-free-release-build",
            [
                "./gradlew",
                ":app:clean",
                ":app:assembleFdroidFullRelease",
                ":app:assembleFdroidSimpleRelease",
                ":app:assembleGithubFullRelease",
                ":app:assembleGithubSimpleRelease",
                ":app:assemblePlayFullRelease",
                ":app:assemblePlaySimpleRelease",
                ":app:bundlePlayFullRelease",
                ":app:assembleGithubFullReleaseAndroidTest",
                "-Pripdpi.testBuildType=release",
                "-Pripdpi.localNativeAbis=host",
                "-Pripdpi.enableAbiSplits=false",
                "--max-workers=1",
            ],
        ),
        ("release-output", ["bash", "scripts/ci/verify-local-release-preflight-output.sh"]),
        ("source-diff", ["git", "diff", "--check", f"{base_tag}..{source_sha}"]),
    ]


def run_preflight(
    repo: Path,
    contract_path: Path,
    release_tag: str,
    window_start_sha: str,
    window_started_at: datetime,
    now: datetime,
    command_runner: CommandRunner = _default_runner,
    candidate_runs: list[dict[str, Any]] | None = None,
    remote_tag_checker: RemoteTagChecker = _ensure_remote_tag_absent,
) -> dict[str, Any]:
    exact_repo = repo.resolve()
    dirty = _git(exact_repo, "status", "--porcelain=v1")
    if dirty:
        raise PreflightError("release preflight requires a clean committed worktree")
    source_sha = _git(exact_repo, "rev-parse", "HEAD")
    tag_check = subprocess.run(
        ["git", "show-ref", "--verify", "--quiet", f"refs/tags/{release_tag}"],
        cwd=exact_repo,
        check=False,
    )
    if tag_check.returncode == 0:
        raise PreflightError(f"target release tag already exists locally: {release_tag}")
    if tag_check.returncode not in (0, 1):
        raise PreflightError("could not verify local target tag absence")
    remote_tag_checker(exact_repo, release_tag)
    base_tag = _latest_stable_tag(exact_repo)
    runs = _candidate_runs(exact_repo) if candidate_runs is None else candidate_runs
    window = evaluate_release_window(
        exact_repo,
        contract_path,
        release_tag,
        window_start_sha,
        source_sha,
        window_started_at,
        now,
        runs,
    )
    checks: list[dict[str, Any]] = []
    for name, command in _commands(release_tag, base_tag, source_sha):
        started = time.monotonic()
        try:
            command_runner(command, exact_repo)
        except (OSError, subprocess.CalledProcessError) as error:
            raise PreflightError(f"release preflight check failed: {name}") from error
        checks.append(
            {
                "name": name,
                "status": "pass",
                "durationSeconds": round(time.monotonic() - started, 3),
                "command": command,
            }
        )
    if _git(exact_repo, "status", "--porcelain=v1"):
        raise PreflightError("release preflight checks changed the committed worktree")
    contract = json.loads(contract_path.read_text(encoding="utf-8"))
    local_contract = contract["localPreflight"]
    return {
        "version": local_contract["reportVersion"],
        "status": "pass",
        "sourceSha": source_sha,
        "releaseTag": release_tag,
        "baseTag": base_tag,
        "createdUtc": now.astimezone(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "window": window,
        "checks": checks,
        "limitations": local_contract["limitations"],
    }


def _timestamp(value: str) -> datetime:
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise argparse.ArgumentTypeError("expected ISO-8601 timestamp") from error
    if result.tzinfo is None:
        raise argparse.ArgumentTypeError("timestamp must include timezone")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=ROOT)
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--window-start-sha", required=True)
    parser.add_argument("--window-started-at", required=True, type=_timestamp)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    if args.report.is_symlink() or (args.report.exists() and not args.report.is_file()):
        parser.error("report path must be a regular file")
    if args.report.exists():
        args.report.unlink()
    try:
        receipt = run_preflight(
            args.repo,
            args.contract,
            args.release_tag,
            args.window_start_sha,
            args.window_started_at,
            datetime.now(UTC),
            candidate_runs=None,
        )
    except (PreflightError, ValueError, KeyError, json.JSONDecodeError) as error:
        parser.error(str(error))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"release preflight: PASS ({args.report})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
