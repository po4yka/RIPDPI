#!/usr/bin/env python3
"""Run the full secret-free local release preflight and emit a bounded receipt."""

from __future__ import annotations

import argparse
import json
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
                "scripts.tests.test_release_p0_contracts",
                "scripts.tests.test_release_p1_contracts",
                "scripts.tests.test_release_window",
                "scripts.tests.test_release_preflight",
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
                ":app:assembleGithubFullRelease",
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
    base_tag = _git(exact_repo, "describe", "--tags", "--abbrev=0", "--match", "v[0-9]*")
    window = evaluate_release_window(
        exact_repo,
        contract_path,
        release_tag,
        window_start_sha,
        source_sha,
        window_started_at,
        now,
        [],
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
        )
    except (PreflightError, ValueError, KeyError, json.JSONDecodeError) as error:
        parser.error(str(error))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"release preflight: PASS ({args.report})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
