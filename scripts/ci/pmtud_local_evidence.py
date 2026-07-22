#!/usr/bin/env python3
"""Run and validate exact-source local MASQUE/H3 PMTUD evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Sequence


MANIFEST_VERSION = "pmtud_local_evidence_v1"
ARTIFACT_VERSION = "pmtud_case_evidence_v1"
SUITE = "masque_h3_pmtud_local"
MAX_VALIDITY = timedelta(hours=6)
MAX_RUN_DURATION = timedelta(hours=1)
FUTURE_SKEW = timedelta(minutes=5)
CASE_TIMEOUT_SECONDS = 300
ROOT = Path(__file__).resolve().parents[2]
RUNNER_RELATIVE = Path("scripts/ci/run-pmtud-local-evidence.sh")
VALIDATOR_RELATIVE = Path("scripts/ci/pmtud_local_evidence.py")
LOCK_RELATIVE = Path("native/rust/Cargo.lock")


@dataclass(frozen=True)
class Case:
    case_id: str
    package: str
    target: str
    test_name: str

    def cargo_args(self) -> tuple[str, ...]:
        target_args = ("--lib",) if self.target == "lib" else ("--test", self.target)
        return (
            "test",
            "--locked",
            "--offline",
            "-p",
            self.package,
            *target_args,
            self.test_name,
            "--",
            "--exact",
            "--nocapture",
        )


REQUIRED_CASES = (
    Case(
        "pmtud_clear_path_control",
        "quic-mtu-test-util",
        "lib",
        "tests::pmtud_enabled_discovers_larger_path_mtu_than_disabled",
    ),
    Case(
        "pmtud_black_hole_fault_control",
        "quic-mtu-test-util",
        "lib",
        "tests::mtu_drop_socket_injects_recoverable_cliff",
    ),
    Case(
        "masque_h3_datagram_payload",
        "ripdpi-masque",
        "lib",
        "tests::h3_connect_udp_honors_root_certificate_and_echoes_context_zero_datagrams",
    ),
    Case(
        "masque_h3_datagram_boundary",
        "ripdpi-masque",
        "lib",
        "tests::h3_connect_udp_boundary_rejects_one_byte_over_limit_without_closing_flow",
    ),
    Case(
        "masque_h3_black_hole_recovery_ipv4",
        "ripdpi-bench",
        "quic_pmtud",
        "masque_h3_recovers_from_mid_connection_mtu_black_hole_ipv4",
    ),
    Case(
        "masque_h3_black_hole_recovery_ipv6_underlay",
        "ripdpi-bench",
        "quic_pmtud",
        "masque_h3_recovers_from_mid_connection_mtu_black_hole_ipv6",
    ),
)

MANIFEST_FIELDS = {
    "artifacts",
    "completedAt",
    "environment",
    "provenance",
    "result",
    "sourceSha",
    "startedAt",
    "suite",
    "validUntil",
    "version",
}
CASE_FIELDS = {
    "artifact",
    "artifactSha256",
    "id",
    "package",
    "result",
    "target",
    "testName",
}
ARTIFACT_FIELDS = {
    "caseId",
    "failed",
    "failureCode",
    "ignored",
    "measured",
    "passed",
    "result",
    "sourceSha",
    "testName",
    "version",
}
ENVIRONMENT_FIELDS = {"architecture", "cargoVersion", "operatingSystem", "rustcVersion"}
PROVENANCE_FIELDS = {
    "cargoLockSha256",
    "runnerSha256",
    "snapshotMethod",
    "suiteDefinitionSha256",
    "validatorSha256",
}
SHA256_RE = re.compile(r"[0-9a-f]{64}")
SOURCE_SHA_RE = re.compile(r"[0-9a-f]{40}")
VERSION_RE = re.compile(
    r"(?:cargo|rustc) \d+\.\d+\.\d+(?:-[a-z0-9.]+)? \([0-9a-f]{7,40} \d{4}-\d{2}-\d{2}\)"
)
SUMMARY_RE = re.compile(
    r"test result: (?:ok|FAILED)\. (\d+) passed; (\d+) failed; (\d+) ignored; (\d+) measured; \d+ filtered out"
)


def canonical_json_bytes(value: Any) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode(
        "utf-8"
    )


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def exact_object(value: Any, fields: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != fields:
        raise ValueError(f"{label} fields must be exactly {sorted(fields)}")
    return value


def parse_utc(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or not re.fullmatch(
        r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value
    ):
        raise ValueError(f"{label} must be UTC with second precision")
    return datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


def format_utc(value: datetime) -> str:
    return (
        value.astimezone(timezone.utc)
        .replace(microsecond=0)
        .strftime("%Y-%m-%dT%H:%M:%SZ")
    )


def parse_test_counts(output: str) -> tuple[int, int, int, int]:
    matches = SUMMARY_RE.findall(output)
    if len(matches) != 1:
        raise ValueError("test output must contain exactly one Rust test summary")
    return tuple(int(value) for value in matches[0])  # type: ignore[return-value]


def parse_test_summary(output: str) -> tuple[int, int, int, int]:
    counts = parse_test_counts(output)
    if counts != (1, 0, 0, 0):
        raise ValueError(
            "exactly one executed PASS and zero failed/ignored/measured tests are required"
        )
    return counts


def suite_definition_sha256() -> str:
    value = [
        {
            "cargoArgs": list(case.cargo_args()),
            "id": case.case_id,
            "package": case.package,
            "target": case.target,
            "testName": case.test_name,
        }
        for case in REQUIRED_CASES
    ]
    return sha256_bytes(canonical_json_bytes(value))


def validate_manifest(
    path: Path,
    *,
    artifact_dir: Path,
    expected_source_sha: str,
    now: datetime | None = None,
    require_pass: bool,
    _verify_source_provenance: bool = True,
) -> dict[str, Any]:
    if not SOURCE_SHA_RE.fullmatch(expected_source_sha):
        raise ValueError("expected source SHA must be 40 lowercase hex characters")
    raw = path.read_bytes()
    manifest = json.loads(raw)
    if raw != canonical_json_bytes(manifest):
        raise ValueError("manifest must use canonical JSON encoding")
    root = exact_object(manifest, MANIFEST_FIELDS, "manifest")
    if root["version"] != MANIFEST_VERSION or root["suite"] != SUITE:
        raise ValueError("manifest version or suite mismatch")
    if root["sourceSha"] != expected_source_sha:
        raise ValueError("manifest source SHA mismatch")
    if root["result"] not in {"PASS", "FAIL"}:
        raise ValueError("manifest result must be PASS or FAIL")

    started = parse_utc(root["startedAt"], "startedAt")
    completed = parse_utc(root["completedAt"], "completedAt")
    valid_until = parse_utc(root["validUntil"], "validUntil")
    observed_now = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    if completed < started or completed - started > MAX_RUN_DURATION:
        raise ValueError("evidence run window is invalid or too long")
    if started > observed_now + FUTURE_SKEW or completed > observed_now + FUTURE_SKEW:
        raise ValueError("evidence timestamps are in the future")
    if (
        valid_until < completed
        or valid_until - completed > MAX_VALIDITY
        or observed_now > valid_until
    ):
        raise ValueError("evidence is stale or has an invalid validity window")

    environment = exact_object(root["environment"], ENVIRONMENT_FIELDS, "environment")
    if environment["operatingSystem"] not in {"darwin", "linux"}:
        raise ValueError("unsupported operating system evidence")
    if not isinstance(environment["architecture"], str) or not re.fullmatch(
        r"[a-z0-9_]{2,24}", environment["architecture"]
    ):
        raise ValueError("invalid redacted architecture")
    for field, prefix in (("cargoVersion", "cargo"), ("rustcVersion", "rustc")):
        value = environment[field]
        if (
            not isinstance(value, str)
            or not VERSION_RE.fullmatch(value)
            or not value.startswith(prefix + " ")
        ):
            raise ValueError(f"invalid redacted {field}")

    provenance = exact_object(root["provenance"], PROVENANCE_FIELDS, "provenance")
    if provenance["snapshotMethod"] != "git-archive":
        raise ValueError("unsupported source snapshot method")
    for field in PROVENANCE_FIELDS - {"snapshotMethod"}:
        if not isinstance(provenance[field], str) or not SHA256_RE.fullmatch(
            provenance[field]
        ):
            raise ValueError(f"invalid {field}")
    if _verify_source_provenance:
        expected_provenance = {
            "cargoLockSha256": sha256_bytes(
                source_blob(ROOT, expected_source_sha, LOCK_RELATIVE)
            ),
            "runnerSha256": sha256_bytes(
                source_blob(ROOT, expected_source_sha, RUNNER_RELATIVE)
            ),
            "snapshotMethod": "git-archive",
            "suiteDefinitionSha256": suite_definition_sha256(),
            "validatorSha256": sha256_bytes(
                source_blob(ROOT, expected_source_sha, VALIDATOR_RELATIVE)
            ),
        }
        if provenance != expected_provenance:
            raise ValueError(
                "manifest provenance does not match the exact source commit"
            )
        if (ROOT / RUNNER_RELATIVE).read_bytes() != source_blob(
            ROOT, expected_source_sha, RUNNER_RELATIVE
        ) or Path(__file__).read_bytes() != source_blob(
            ROOT, expected_source_sha, VALIDATOR_RELATIVE
        ):
            raise ValueError(
                "validator checkout does not match the exact source commit"
            )

    rows = root["artifacts"]
    if not isinstance(rows, list):
        raise ValueError("artifacts must be an array")
    expected_ids = [case.case_id for case in REQUIRED_CASES]
    if [
        row.get("id") if isinstance(row, dict) else None for row in rows
    ] != expected_ids:
        raise ValueError("artifacts must follow the required case sequence exactly")
    expected_artifact_names = {f"{case.case_id}.json" for case in REQUIRED_CASES}
    if (
        not artifact_dir.is_dir()
        or {child.name for child in artifact_dir.iterdir()} != expected_artifact_names
    ):
        raise ValueError("artifact set must contain exactly the required case files")

    all_pass = True
    for case, raw_row in zip(REQUIRED_CASES, rows, strict=True):
        row = exact_object(raw_row, CASE_FIELDS, f"case {case.case_id}")
        expected_metadata = (
            case.package,
            case.target,
            case.test_name,
            f"{case.case_id}.json",
        )
        observed_metadata = (
            row["package"],
            row["target"],
            row["testName"],
            row["artifact"],
        )
        if observed_metadata != expected_metadata:
            raise ValueError(f"case metadata mismatch for {case.case_id}")
        if row["result"] not in {"PASS", "FAIL"}:
            raise ValueError(f"case result is not real for {case.case_id}")
        artifact_path = artifact_dir / row["artifact"]
        if artifact_path.is_symlink() or not artifact_path.is_file():
            raise ValueError(f"artifact must be a regular file for {case.case_id}")
        artifact_raw = artifact_path.read_bytes()
        if (
            not isinstance(row["artifactSha256"], str)
            or sha256_bytes(artifact_raw) != row["artifactSha256"]
        ):
            raise ValueError(f"artifact digest mismatch for {case.case_id}")
        artifact = json.loads(artifact_raw)
        if artifact_raw != canonical_json_bytes(artifact):
            raise ValueError(f"artifact must be canonical for {case.case_id}")
        artifact = exact_object(artifact, ARTIFACT_FIELDS, f"artifact {case.case_id}")
        if (
            artifact["version"] != ARTIFACT_VERSION
            or artifact["caseId"] != case.case_id
            or artifact["sourceSha"] != expected_source_sha
            or artifact["testName"] != case.test_name
            or artifact["result"] != row["result"]
        ):
            raise ValueError(f"artifact binding mismatch for {case.case_id}")
        counts = tuple(
            artifact[field] for field in ("passed", "failed", "ignored", "measured")
        )
        if not all(
            isinstance(value, int) and not isinstance(value, bool) and value >= 0
            for value in counts
        ):
            raise ValueError(f"artifact counts are invalid for {case.case_id}")
        if artifact["result"] == "PASS":
            if counts != (1, 0, 0, 0) or artifact["failureCode"] != "NONE":
                raise ValueError(f"PASS artifact counts are invalid for {case.case_id}")
        else:
            all_pass = False
            if counts[0] != 0 or counts[1] < 1 or counts[2:] != (0, 0):
                raise ValueError(f"FAIL artifact counts are invalid for {case.case_id}")
            if artifact["failureCode"] not in {
                "COMMAND_FAILED",
                "SUMMARY_INVALID",
                "TIMEOUT",
            }:
                raise ValueError(f"FAIL artifact reason is invalid for {case.case_id}")

    if (root["result"] == "PASS") != all_pass:
        raise ValueError("manifest result does not match case results")
    if require_pass and root["result"] != "PASS":
        raise ValueError("PASS required for the PMTUD local evidence gate")
    return root


def git(repo: Path, *args: str, text: bool = True) -> str | bytes:
    completed = subprocess.run(
        ["git", "-C", str(repo), *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=text,
    )
    return completed.stdout


def source_blob(repo: Path, source_sha: str, relative: Path) -> bytes:
    value = git(repo, "show", f"{source_sha}:{relative.as_posix()}", text=False)
    assert isinstance(value, bytes)
    return value


def safe_tool_version(executable: str, expected_prefix: str) -> str:
    completed = subprocess.run(
        [executable, "--version"],
        check=True,
        capture_output=True,
        text=True,
        timeout=15,
    )
    value = completed.stdout.strip()
    if not VERSION_RE.fullmatch(value) or not value.startswith(expected_prefix + " "):
        raise ValueError(f"unsafe or unexpected {expected_prefix} version output")
    return value


def test_environment(target_dir: Path) -> dict[str, str]:
    allowed = (
        "HOME",
        "CARGO_HOME",
        "RUSTUP_HOME",
        "SSL_CERT_FILE",
        "SSL_CERT_DIR",
        "TMPDIR",
    )
    result = {key: os.environ[key] for key in allowed if key in os.environ}
    result.update(
        {
            "CARGO_INCREMENTAL": "0",
            "CARGO_TARGET_DIR": str(target_dir),
            "CARGO_TERM_COLOR": "never",
            "LANG": "C",
            "LC_ALL": "C",
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "RUST_BACKTRACE": "0",
            "TZ": "UTC",
        }
    )
    return result


def run_case(
    cargo: str, rust_root: Path, target_dir: Path, case: Case
) -> tuple[dict[str, Any], str]:
    process = subprocess.Popen(
        [cargo, *case.cargo_args()],
        cwd=rust_root,
        env=test_environment(target_dir),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        start_new_session=True,
    )
    try:
        output, _ = process.communicate(timeout=CASE_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            output, _ = process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            output, _ = process.communicate()
        return {
            "failed": 1,
            "failureCode": "TIMEOUT",
            "ignored": 0,
            "measured": 0,
            "passed": 0,
            "result": "FAIL",
        }, output

    try:
        counts = parse_test_counts(output)
    except ValueError:
        return {
            "failed": 1,
            "failureCode": "SUMMARY_INVALID",
            "ignored": 0,
            "measured": 0,
            "passed": 0,
            "result": "FAIL",
        }, output
    if process.returncode == 0 and counts == (1, 0, 0, 0):
        return {
            "failed": 0,
            "failureCode": "NONE",
            "ignored": 0,
            "measured": 0,
            "passed": 1,
            "result": "PASS",
        }, output
    return {
        "failed": max(1, counts[1]),
        "failureCode": "COMMAND_FAILED",
        "ignored": 0,
        "measured": 0,
        "passed": 0,
        "result": "FAIL",
    }, output


def run_evidence(output_dir: Path, *, repo: Path = ROOT) -> Path:
    if output_dir.exists() and (output_dir.is_symlink() or any(output_dir.iterdir())):
        raise ValueError(
            "output directory must be absent or empty and must not be a symlink"
        )
    output_dir.mkdir(parents=True, exist_ok=True)
    artifact_dir = output_dir / "artifacts"
    artifact_dir.mkdir()

    source_sha = str(git(repo, "rev-parse", "HEAD")).strip()
    if not SOURCE_SHA_RE.fullmatch(source_sha):
        raise ValueError("source SHA is invalid")
    if str(git(repo, "status", "--porcelain=v1", "--untracked-files=all")).strip():
        raise ValueError("exact-SHA evidence requires a clean worktree")
    runner_bytes = source_blob(repo, source_sha, RUNNER_RELATIVE)
    validator_bytes = source_blob(repo, source_sha, VALIDATOR_RELATIVE)
    if (repo / RUNNER_RELATIVE).read_bytes() != runner_bytes or Path(
        __file__
    ).read_bytes() != validator_bytes:
        raise ValueError(
            "runner and validator must match their exact source commit blobs"
        )
    lock_bytes = source_blob(repo, source_sha, LOCK_RELATIVE)

    cargo = shutil.which("cargo")
    rustc = shutil.which("rustc")
    if cargo is None or rustc is None:
        raise RuntimeError("cargo and rustc are required")
    environment = {
        "architecture": platform.machine().lower(),
        "cargoVersion": safe_tool_version(cargo, "cargo"),
        "operatingSystem": platform.system().lower(),
        "rustcVersion": safe_tool_version(rustc, "rustc"),
    }
    started = datetime.now(timezone.utc).replace(microsecond=0)
    rows: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="ripdpi-pmtud-snapshot-") as temporary:
        temporary_root = Path(temporary)
        archive_path = temporary_root / "source.tar"
        snapshot_root = temporary_root / "source"
        snapshot_root.mkdir()
        subprocess.run(
            [
                "git",
                "-C",
                str(repo),
                "archive",
                "--format=tar",
                "-o",
                str(archive_path),
                source_sha,
            ],
            check=True,
        )
        with tarfile.open(archive_path, "r") as archive:
            archive.extractall(snapshot_root)
        target_dir = repo / "native/rust/target/pmtud-local-evidence"
        for case in REQUIRED_CASES:
            result, _discarded_raw_output = run_case(
                cargo, snapshot_root / "native/rust", target_dir, case
            )
            artifact = {
                "caseId": case.case_id,
                **result,
                "sourceSha": source_sha,
                "testName": case.test_name,
                "version": ARTIFACT_VERSION,
            }
            artifact_path = artifact_dir / f"{case.case_id}.json"
            artifact_path.write_bytes(canonical_json_bytes(artifact))
            rows.append(
                {
                    "artifact": artifact_path.name,
                    "artifactSha256": sha256_bytes(artifact_path.read_bytes()),
                    "id": case.case_id,
                    "package": case.package,
                    "result": result["result"],
                    "target": case.target,
                    "testName": case.test_name,
                }
            )

    completed = datetime.now(timezone.utc).replace(microsecond=0)
    if (
        str(git(repo, "rev-parse", "HEAD")).strip() != source_sha
        or str(git(repo, "status", "--porcelain=v1", "--untracked-files=all")).strip()
    ):
        raise ValueError("source worktree changed during evidence execution")
    result = "PASS" if all(row["result"] == "PASS" for row in rows) else "FAIL"
    manifest = {
        "artifacts": rows,
        "completedAt": format_utc(completed),
        "environment": environment,
        "provenance": {
            "cargoLockSha256": sha256_bytes(lock_bytes),
            "runnerSha256": sha256_bytes(runner_bytes),
            "snapshotMethod": "git-archive",
            "suiteDefinitionSha256": suite_definition_sha256(),
            "validatorSha256": sha256_bytes(validator_bytes),
        },
        "result": result,
        "sourceSha": source_sha,
        "startedAt": format_utc(started),
        "suite": SUITE,
        "validUntil": format_utc(completed + MAX_VALIDITY),
        "version": MANIFEST_VERSION,
    }
    manifest_path = output_dir / "manifest.json"
    manifest_path.write_bytes(canonical_json_bytes(manifest))
    validate_manifest(
        manifest_path,
        artifact_dir=artifact_dir,
        expected_source_sha=source_sha,
        require_pass=True,
    )
    return manifest_path


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description=__doc__)
    subparsers = value.add_subparsers(dest="command", required=True)
    run = subparsers.add_parser("run", help="run the exact-source local PMTUD suite")
    run.add_argument("--output-dir", type=Path, required=True)
    validate = subparsers.add_parser(
        "validate", help="validate an existing PMTUD manifest"
    )
    validate.add_argument("--manifest", type=Path, required=True)
    validate.add_argument("--artifact-dir", type=Path, required=True)
    validate.add_argument("--source-sha", required=True)
    return value


def main(argv: Sequence[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        if args.command == "run":
            path = run_evidence(args.output_dir)
            print(path)
        else:
            validate_manifest(
                args.manifest,
                artifact_dir=args.artifact_dir,
                expected_source_sha=args.source_sha,
                require_pass=True,
            )
            print("PMTUD local evidence valid")
    except (
        OSError,
        RuntimeError,
        ValueError,
        subprocess.SubprocessError,
        json.JSONDecodeError,
    ) as error:
        print(f"PMTUD local evidence rejected: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
