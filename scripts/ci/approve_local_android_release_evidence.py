#!/usr/bin/env python3
"""Fail-closed local Android release-evidence acceptance entrypoint."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tarfile
import tempfile


ROOT = Path(__file__).resolve().parents[2]
SELF_PATH = Path(__file__).resolve()
SELF_REPO_PATH = "scripts/ci/approve_local_android_release_evidence.py"
CHECKER_REPO_PATH = "scripts/ci/check_dns_ipv6_killswitch_gates.py"
EXECUTION_ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
MAX_EVIDENCE_FILES = 32
MAX_EVIDENCE_BYTES = 64 * 1024 * 1024


def git(*args: str, text: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", "-C", str(ROOT), *args],
        check=True,
        capture_output=True,
        text=text,
    )


def git_text(*args: str) -> str:
    return git(*args).stdout.strip()


def read_regular_file(path: Path, label: str) -> bytes:
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except OSError as error:
        raise ValueError(
            f"{label} must be a readable non-symlink file: {path}"
        ) from error
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode):
            raise ValueError(f"{label} must be a regular file: {path}")
        if metadata.st_size > MAX_EVIDENCE_BYTES:
            raise ValueError(f"{label} exceeds the local evidence size limit")
        with os.fdopen(descriptor, "rb", closefd=False) as handle:
            return handle.read()
    finally:
        os.close(descriptor)


def copy_evidence_snapshot(manifest: Path, destination: Path) -> Path:
    source_dir = manifest.parent.resolve(strict=True)
    entries = list(os.scandir(source_dir))
    if len(entries) > MAX_EVIDENCE_FILES:
        raise ValueError("evidence directory contains too many files")
    total = 0
    manifest_name = manifest.name
    for entry in entries:
        source = Path(entry.path)
        payload = read_regular_file(source, f"evidence entry {entry.name}")
        total += len(payload)
        if total > MAX_EVIDENCE_BYTES:
            raise ValueError("evidence directory exceeds the local evidence size limit")
        target = destination / entry.name
        target.write_bytes(payload)
        target.chmod(0o600)
    copied_manifest = destination / manifest_name
    if not copied_manifest.is_file():
        raise ValueError("evidence manifest was not captured in its directory snapshot")
    return copied_manifest


def reject_hidden_index_state() -> None:
    tagged = git("ls-files", "-v", "-z", text=False).stdout.split(b"\0")
    hidden = []
    for entry in tagged:
        if not entry:
            continue
        tag = chr(entry[0])
        if tag.islower() or tag == "S":
            hidden.append(entry[2:].decode("utf-8", errors="replace"))
    if hidden:
        raise ValueError(
            "source checkout contains assume-unchanged or skip-worktree paths"
        )


def extract_head_validator(destination: Path) -> Path:
    archive = git(
        "archive",
        "--format=tar",
        "HEAD",
        "scripts/ci",
        "quality/release-gates",
        "test-lab/scripts/run-dual-vantage-network-evidence.sh",
        text=False,
    ).stdout
    archive_path = destination / "validator.tar"
    archive_path.write_bytes(archive)
    with tarfile.open(archive_path, "r:") as handle:
        handle.extractall(destination, filter="data")
    archive_path.unlink()
    checker = destination / CHECKER_REPO_PATH
    if not checker.is_file() or checker.is_symlink():
        raise ValueError("HEAD does not contain the local release checker")
    return checker


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True, type=Path)
    parser.add_argument("--evidence-manifest", required=True, type=Path)
    parser.add_argument("--execution-id", required=True)
    parser.add_argument("--execution-attempt", required=True, type=int)
    args = parser.parse_args(argv)
    if EXECUTION_ID_PATTERN.fullmatch(args.execution_id) is None:
        parser.error("--execution-id must be a bounded local run identifier")
    if args.execution_attempt < 1:
        parser.error("--execution-attempt must be positive")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if git_text("rev-parse", "--show-toplevel") != str(ROOT):
            raise ValueError("entrypoint is not running from its owning Git checkout")
        source_sha = git_text("rev-parse", "HEAD")
        if re.fullmatch(r"[0-9a-f]{40}", source_sha) is None:
            raise ValueError("HEAD is not an exact lowercase Git SHA-1")
        committed_self = git("show", f"HEAD:{SELF_REPO_PATH}", text=False).stdout
        if read_regular_file(SELF_PATH, "acceptance entrypoint") != committed_self:
            raise ValueError("acceptance entrypoint does not match exact HEAD")
        reject_hidden_index_state()
        if git_text("status", "--porcelain", "--untracked-files=all"):
            raise ValueError(
                "source checkout is dirty; local release acceptance refused"
            )

        with tempfile.TemporaryDirectory(prefix="ripdpi-local-release-") as temporary:
            snapshot = Path(temporary)
            snapshot.chmod(0o700)
            validator_root = snapshot / "source"
            validator_root.mkdir(mode=0o700)
            checker = extract_head_validator(validator_root)
            evidence_dir = snapshot / "evidence"
            evidence_dir.mkdir(mode=0o700)
            evidence = copy_evidence_snapshot(args.evidence_manifest, evidence_dir)
            results = snapshot / "ordinary-results.json"
            results.write_bytes(read_regular_file(args.results, "ordinary results"))
            results.chmod(0o600)

            completed = subprocess.run(
                [
                    sys.executable,
                    str(checker),
                    "--results",
                    str(results),
                    "--evidence-manifest",
                    str(evidence),
                    "--applies-to",
                    "android-client-release",
                    "--expected-source-sha",
                    source_sha,
                    "--expected-execution-kind",
                    "local",
                    "--expected-execution-id",
                    args.execution_id,
                    "--expected-execution-attempt",
                    str(args.execution_attempt),
                ],
                cwd=validator_root,
                check=False,
            )
        if completed.returncode != 0:
            return completed.returncode
        if git_text("rev-parse", "HEAD") != source_sha:
            raise ValueError("HEAD changed during local release acceptance")
        if read_regular_file(SELF_PATH, "acceptance entrypoint") != committed_self:
            raise ValueError(
                "acceptance entrypoint changed during local release acceptance"
            )
        print(f"local Android release evidence accepted for {source_sha}")
        return 0
    except (
        OSError,
        subprocess.CalledProcessError,
        tarfile.TarError,
        ValueError,
    ) as error:
        print(f"local Android release evidence rejected: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
