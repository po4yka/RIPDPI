#!/usr/bin/env python3
"""Run and validate exact-source local MASQUE/H3 PMTUD evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import pwd
import re
import signal
import subprocess
import sys
import tarfile
import tempfile
import tomllib
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Sequence


MANIFEST_VERSION = "pmtud_local_evidence_v3"
ARTIFACT_VERSION = "pmtud_case_evidence_v3"
MEASUREMENT_VERSION = "pmtud_measurement_v1"
MEASUREMENT_MARKER = "PMTUD_MEASUREMENT "
SUITE = "masque_h3_pmtud_local"
MAX_VALIDITY = timedelta(hours=6)
MAX_RUN_DURATION = timedelta(hours=1)
FUTURE_SKEW = timedelta(minutes=5)
CASE_TIMEOUT_SECONDS = 300
ROOT = Path(__file__).resolve().parents[2]
RUNNER_RELATIVE = Path("scripts/ci/run-pmtud-local-evidence.sh")
VALIDATOR_RELATIVE = Path("scripts/ci/pmtud_local_evidence.py")
LOCK_RELATIVE = Path("native/rust/Cargo.lock")
TOOLCHAIN_RELATIVE = Path("native/rust/rust-toolchain.toml")
CARGO_CONFIG_RELATIVE = Path("native/rust/.cargo/config.toml")


@dataclass(frozen=True)
class Case:
    case_id: str
    package: str
    target: str
    test_name: str
    measurement_profile: str
    target_family: str = "ipv4"

    def cargo_compile_args(self) -> tuple[str, ...]:
        target_args = ("--lib",) if self.target == "lib" else ("--test", self.target)
        return (
            "test",
            "--locked",
            "--offline",
            "-p",
            self.package,
            *target_args,
            "--no-run",
            "--message-format=json-render-diagnostics",
        )

    def executable_args(self) -> tuple[str, ...]:
        return (
            self.test_name,
            "--exact",
            "--nocapture",
            "--test-threads=1",
        )


@dataclass(frozen=True)
class TrustedToolchain:
    channel: str
    rustup: Path
    cargo: Path
    rustc: Path
    rustdoc: Path
    cargo_version: str
    rustc_version: str


REQUIRED_CASES = (
    Case(
        "pmtud_clear_path_control",
        "quic-mtu-test-util",
        "lib",
        "tests::pmtud_enabled_discovers_larger_path_mtu_than_disabled",
        "clear_path",
    ),
    Case(
        "pmtud_black_hole_fault_control",
        "quic-mtu-test-util",
        "lib",
        "tests::mtu_drop_socket_injects_recoverable_cliff",
        "black_hole",
    ),
    Case(
        "masque_h3_datagram_payload",
        "ripdpi-masque",
        "lib",
        "tests::h3_connect_udp_honors_root_certificate_and_echoes_context_zero_datagrams",
        "datagram",
    ),
    Case(
        "masque_h3_datagram_boundary",
        "ripdpi-masque",
        "lib",
        "tests::h3_connect_udp_boundary_rejects_one_byte_over_limit_without_closing_flow",
        "boundary",
    ),
    Case(
        "masque_h3_black_hole_recovery_ipv4",
        "ripdpi-bench",
        "quic_pmtud",
        "masque_h3_recovers_from_mid_connection_mtu_black_hole_ipv4",
        "black_hole",
    ),
    Case(
        "masque_h3_black_hole_recovery_ipv6_target",
        "ripdpi-bench",
        "quic_pmtud",
        "masque_h3_recovers_from_mid_connection_mtu_black_hole_ipv6",
        "black_hole",
        "ipv6",
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
    "executableSha256",
    "id",
    "package",
    "result",
    "target",
    "testName",
}
ARTIFACT_FIELDS = {
    "caseId",
    "executableSha256",
    "failed",
    "failureCode",
    "ignored",
    "measured",
    "measurement",
    "passed",
    "result",
    "sourceSha",
    "testName",
    "toolchainChannel",
    "version",
}
MEASUREMENT_FIELDS = {
    "blackHoleDelta",
    "caseId",
    "highMtu",
    "integrity",
    "oversizedDropDelta",
    "payloadLength",
    "payloadSha256",
    "postCliffMtu",
    "preMtu",
    "targetFamily",
    "version",
}
ENVIRONMENT_FIELDS = {
    "architecture",
    "cargoExecutableSha256",
    "cargoVersion",
    "operatingSystem",
    "rustcExecutableSha256",
    "rustcVersion",
    "rustdocExecutableSha256",
    "rustupExecutableSha256",
    "toolchainChannel",
}
PROVENANCE_FIELDS = {
    "cargoConfigSha256",
    "cargoLockSha256",
    "runnerSha256",
    "snapshotMethod",
    "suiteDefinitionSha256",
    "toolchainFileSha256",
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


def repeated_payload_sha256(byte: int, length: int) -> str:
    return sha256_bytes(bytes([byte]) * length)


def expected_payload_sha256(case: Case, length: int) -> str:
    if case.measurement_profile in {"clear_path", "black_hole"}:
        if case.package == "quic-mtu-test-util":
            return repeated_payload_sha256(0xC3, length)
        return sha256_bytes(b"post-recovery-integrity")
    if case.measurement_profile == "datagram":
        return sha256_bytes(b"h3-pinned-root")
    if case.measurement_profile == "boundary":
        return repeated_payload_sha256(0xA5, length)
    raise ValueError(f"unknown measurement profile for {case.case_id}")


def example_measurement(case: Case) -> dict[str, Any]:
    payload_length = {
        "clear_path": 256 * 1024,
        "black_hole": 256 * 1024 if case.package == "quic-mtu-test-util" else 23,
        "datagram": 14,
        "boundary": 1400,
    }[case.measurement_profile]
    has_cliff = case.measurement_profile == "black_hole"
    return {
        "blackHoleDelta": 1 if has_cliff else 0,
        "caseId": case.case_id,
        "highMtu": 1452,
        "integrity": True,
        "oversizedDropDelta": 1 if has_cliff or case.measurement_profile == "boundary" else 0,
        "payloadLength": payload_length,
        "payloadSha256": expected_payload_sha256(case, payload_length),
        "postCliffMtu": 1200 if has_cliff else None,
        "preMtu": 1200 if case.measurement_profile == "clear_path" else 1452,
        "targetFamily": case.target_family,
        "version": MEASUREMENT_VERSION,
    }


def validate_measurement(value: Any, case: Case) -> dict[str, Any]:
    measurement = exact_object(value, MEASUREMENT_FIELDS, f"measurement {case.case_id}")
    if (
        measurement["version"] != MEASUREMENT_VERSION
        or measurement["caseId"] != case.case_id
        or measurement["targetFamily"] != case.target_family
        or measurement["integrity"] is not True
    ):
        raise ValueError(f"measurement binding or integrity mismatch for {case.case_id}")
    integer_fields = (
        "blackHoleDelta",
        "highMtu",
        "oversizedDropDelta",
        "payloadLength",
        "preMtu",
    )
    if not all(
        isinstance(measurement[field], int)
        and not isinstance(measurement[field], bool)
        and measurement[field] >= 0
        for field in integer_fields
    ):
        raise ValueError(f"measurement integers are invalid for {case.case_id}")
    post_cliff = measurement["postCliffMtu"]
    if post_cliff is not None and (
        not isinstance(post_cliff, int)
        or isinstance(post_cliff, bool)
        or post_cliff < 0
    ):
        raise ValueError(f"post-cliff MTU is invalid for {case.case_id}")
    payload_length = measurement["payloadLength"]
    if payload_length <= 0 or payload_length > 1_048_576:
        raise ValueError(f"payload length is out of range for {case.case_id}")
    payload_sha = measurement["payloadSha256"]
    if not isinstance(payload_sha, str) or not SHA256_RE.fullmatch(payload_sha):
        raise ValueError(f"payload SHA-256 is invalid for {case.case_id}")
    if payload_sha != expected_payload_sha256(case, payload_length):
        raise ValueError(f"payload SHA-256 and length are inconsistent for {case.case_id}")

    pre_mtu = measurement["preMtu"]
    high_mtu = measurement["highMtu"]
    drop_delta = measurement["oversizedDropDelta"]
    black_hole_delta = measurement["blackHoleDelta"]
    if not 1200 <= pre_mtu <= 65_527 or not 1200 <= high_mtu <= 65_527:
        raise ValueError(f"MTU observations are out of range for {case.case_id}")
    if case.measurement_profile == "clear_path":
        if (
            not (pre_mtu <= 1200 < high_mtu)
            or post_cliff is not None
            or drop_delta != 0
            or black_hole_delta != 0
            or payload_length != 256 * 1024
        ):
            raise ValueError(f"clear-path measurements are inconsistent for {case.case_id}")
    elif case.measurement_profile == "black_hole":
        expected_length = 256 * 1024 if case.package == "quic-mtu-test-util" else 23
        if (
            high_mtu < pre_mtu
            or not isinstance(post_cliff, int)
            or not 1200 <= post_cliff <= 1300
            or post_cliff >= high_mtu
            or drop_delta < 1
            or black_hole_delta < 1
            or payload_length != expected_length
        ):
            raise ValueError(f"black-hole measurements are inconsistent for {case.case_id}")
    elif case.measurement_profile == "datagram":
        if (
            high_mtu < pre_mtu
            or post_cliff is not None
            or drop_delta != 0
            or black_hole_delta != 0
            or payload_length != 14
        ):
            raise ValueError(f"datagram measurements are inconsistent for {case.case_id}")
    elif case.measurement_profile == "boundary":
        if (
            high_mtu < 1400
            or high_mtu < pre_mtu
            or post_cliff is not None
            or drop_delta != 1
            or black_hole_delta != 0
            or not 1200 <= payload_length <= 65_527
        ):
            raise ValueError(f"boundary measurements are inconsistent for {case.case_id}")
    return measurement


def parse_measurement(output: str, case: Case) -> dict[str, Any]:
    markers = [
        line.split(MEASUREMENT_MARKER, 1)[1]
        for line in output.splitlines()
        if MEASUREMENT_MARKER in line
    ]
    if len(markers) != 1:
        raise ValueError(f"test output must contain exactly one measurement marker for {case.case_id}")
    try:
        value = json.loads(markers[0])
    except json.JSONDecodeError as error:
        raise ValueError(f"measurement JSON is malformed for {case.case_id}") from error
    return validate_measurement(value, case)


def suite_definition_sha256() -> str:
    value = [
        {
            "cargoCompileArgs": list(case.cargo_compile_args()),
            "executableArgs": list(case.executable_args()),
            "id": case.case_id,
            "measurementProfile": case.measurement_profile,
            "package": case.package,
            "target": case.target,
            "targetFamily": case.target_family,
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
    channel = environment["toolchainChannel"]
    if not isinstance(channel, str) or not re.fullmatch(r"\d+\.\d+\.\d+", channel):
        raise ValueError("invalid pinned toolchain channel")
    if not environment["cargoVersion"].startswith(f"cargo {channel} ") or not environment["rustcVersion"].startswith(
        f"rustc {channel} "
    ):
        raise ValueError("tool versions do not match the pinned toolchain channel")
    for field in (
        "cargoExecutableSha256",
        "rustcExecutableSha256",
        "rustdocExecutableSha256",
        "rustupExecutableSha256",
    ):
        if not isinstance(environment[field], str) or not SHA256_RE.fullmatch(environment[field]):
            raise ValueError(f"invalid {field}")

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
            "cargoConfigSha256": sha256_bytes(source_blob(ROOT, expected_source_sha, CARGO_CONFIG_RELATIVE)),
            "cargoLockSha256": sha256_bytes(
                source_blob(ROOT, expected_source_sha, LOCK_RELATIVE)
            ),
            "runnerSha256": sha256_bytes(
                source_blob(ROOT, expected_source_sha, RUNNER_RELATIVE)
            ),
            "snapshotMethod": "git-archive",
            "suiteDefinitionSha256": suite_definition_sha256(),
            "toolchainFileSha256": sha256_bytes(source_blob(ROOT, expected_source_sha, TOOLCHAIN_RELATIVE)),
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
            or artifact["toolchainChannel"] != channel
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
            if (
                not isinstance(artifact["executableSha256"], str)
                or not SHA256_RE.fullmatch(artifact["executableSha256"])
                or row["executableSha256"] != artifact["executableSha256"]
            ):
                raise ValueError(f"PASS executable digest is invalid for {case.case_id}")
            validate_measurement(artifact["measurement"], case)
        else:
            all_pass = False
            if counts[0] != 0 or counts[1] < 1 or counts[2:] != (0, 0):
                raise ValueError(f"FAIL artifact counts are invalid for {case.case_id}")
            if artifact["failureCode"] not in {
                "COMMAND_FAILED",
                "BUILD_FAILED",
                "EXECUTABLE_INVALID",
                "MEASUREMENT_INVALID",
                "SUMMARY_INVALID",
                "TIMEOUT",
            }:
                raise ValueError(f"FAIL artifact reason is invalid for {case.case_id}")
            executable_digest = artifact["executableSha256"]
            if executable_digest is not None and (
                not isinstance(executable_digest, str) or not SHA256_RE.fullmatch(executable_digest)
            ):
                raise ValueError(f"FAIL executable digest is invalid for {case.case_id}")
            if row["executableSha256"] != executable_digest:
                raise ValueError(f"FAIL executable digest binding mismatch for {case.case_id}")
            if artifact["measurement"] is not None:
                validate_measurement(artifact["measurement"], case)

    if (root["result"] == "PASS") != all_pass:
        raise ValueError("manifest result does not match case results")
    if require_pass and root["result"] != "PASS":
        raise ValueError("PASS required for the PMTUD local evidence gate")
    return root


def trusted_git_path() -> Path:
    candidate = Path("/usr/bin/git")
    try:
        resolved = candidate.resolve(strict=True)
    except OSError as error:
        raise RuntimeError("trusted Git executable is unavailable") from error
    if not resolved.is_file() or not os.access(resolved, os.X_OK):
        raise RuntimeError("trusted Git executable is not executable")
    return resolved


def git(repo: Path, *args: str, text: bool = True) -> str | bytes:
    completed = subprocess.run(
        [str(trusted_git_path()), "-C", str(repo), *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=text,
        env={
            "HOME": str(Path(pwd.getpwuid(os.getuid()).pw_dir)),
            "LANG": "C",
            "LC_ALL": "C",
            "PATH": trusted_path(),
            "TZ": "UTC",
        },
    )
    return completed.stdout


def source_blob(repo: Path, source_sha: str, relative: Path) -> bytes:
    value = git(repo, "show", f"{source_sha}:{relative.as_posix()}", text=False)
    assert isinstance(value, bytes)
    return value


def reject_environment_overrides(environment: dict[str, str] | os._Environ[str]) -> None:
    exact = {
        "CARGO_BUILD_RUSTC",
        "CARGO_BUILD_RUSTC_WRAPPER",
        "CARGO_BUILD_RUSTC_WORKSPACE_WRAPPER",
        "CARGO_BUILD_TARGET",
        "CARGO_HOME",
        "RUSTC",
        "RUSTC_WRAPPER",
        "RUSTC_WORKSPACE_WRAPPER",
        "RUSTDOC",
        "RUSTUP_HOME",
        "RUSTUP_TOOLCHAIN",
    }
    forbidden = sorted(
        key
        for key in environment
        if key in exact
        or (key.startswith("CARGO_TARGET_") and key.endswith("_RUNNER"))
        or key.startswith("CARGO_ALIAS_")
    )
    if forbidden:
        raise ValueError(f"unsafe build environment override: {forbidden[0]}")


def validate_cargo_config(path: Path) -> None:
    try:
        value = tomllib.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, tomllib.TOMLDecodeError) as error:
        raise ValueError(f"invalid Cargo config: {path.name}") from error

    def walk(node: Any, keys: tuple[str, ...] = ()) -> None:
        if not isinstance(node, dict):
            return
        for key, child in node.items():
            path_keys = (*keys, key)
            unsafe = key == "runner" or path_keys == ("paths",) or (
                keys == ("build",)
                and key in {"rustc", "rustc-wrapper", "rustc-workspace-wrapper", "target"}
            )
            if unsafe:
                raise ValueError(f"unsafe Cargo config execution override: {'.'.join(path_keys)}")
            walk(child, path_keys)

    walk(value)


def trusted_rustup_path() -> Path:
    home = Path(pwd.getpwuid(os.getuid()).pw_dir)
    candidate = home / ".cargo/bin/rustup"
    try:
        resolved = candidate.resolve(strict=True)
    except OSError as error:
        raise RuntimeError("trusted rustup executable is unavailable") from error
    if not resolved.is_file() or not os.access(resolved, os.X_OK):
        raise RuntimeError("trusted rustup executable is not executable")
    return resolved


def trusted_path() -> str:
    candidates = (
        Path("/usr/bin"),
        Path("/bin"),
        Path("/usr/sbin"),
        Path("/sbin"),
        Path("/opt/homebrew/bin"),
        Path("/usr/local/bin"),
    )
    return os.pathsep.join(str(path) for path in candidates if path.is_dir())


def tool_resolution_environment() -> dict[str, str]:
    home = Path(pwd.getpwuid(os.getuid()).pw_dir)
    return {
        "HOME": str(home),
        "LANG": "C",
        "LC_ALL": "C",
        "PATH": trusted_path(),
        "RUSTUP_HOME": str(home / ".rustup"),
        "TZ": "UTC",
    }


def safe_tool_version(executable: Path, expected_prefix: str, environment: dict[str, str]) -> str:
    completed = subprocess.run(
        [str(executable), "--version"],
        check=True,
        capture_output=True,
        text=True,
        timeout=15,
        env=environment,
    )
    value = completed.stdout.strip()
    if not VERSION_RE.fullmatch(value) or not value.startswith(expected_prefix + " "):
        raise ValueError(f"unsafe or unexpected {expected_prefix} version output")
    return value


def resolve_trusted_toolchain(
    channel: str, *, caller_environment: dict[str, str] | os._Environ[str] = os.environ
) -> TrustedToolchain:
    reject_environment_overrides(caller_environment)
    if not re.fullmatch(r"\d+\.\d+\.\d+", channel):
        raise ValueError("rust-toolchain channel must be an exact stable version")
    rustup = trusted_rustup_path()
    environment = tool_resolution_environment()

    def resolve(component: str) -> Path:
        completed = subprocess.run(
            [str(rustup), "which", "--toolchain", channel, component],
            check=True,
            capture_output=True,
            text=True,
            timeout=15,
            env=environment,
        )
        try:
            path = Path(completed.stdout.strip()).resolve(strict=True)
        except OSError as error:
            raise RuntimeError(f"trusted {component} executable is unavailable") from error
        if not path.is_file() or not os.access(path, os.X_OK):
            raise RuntimeError(f"trusted {component} executable is not executable")
        return path

    cargo = resolve("cargo")
    rustc = resolve("rustc")
    rustdoc = resolve("rustdoc")
    cargo_version = safe_tool_version(cargo, "cargo", environment)
    rustc_version = safe_tool_version(rustc, "rustc", environment)
    if not cargo_version.startswith(f"cargo {channel} ") or not rustc_version.startswith(f"rustc {channel} "):
        raise ValueError("resolved Rust tools do not match the pinned toolchain channel")
    return TrustedToolchain(channel, rustup, cargo, rustc, rustdoc, cargo_version, rustc_version)


def read_toolchain_channel(path: Path) -> str:
    try:
        value = tomllib.loads(path.read_text(encoding="utf-8"))
        channel = value["toolchain"]["channel"]
    except (OSError, UnicodeError, KeyError, TypeError, tomllib.TOMLDecodeError) as error:
        raise ValueError("invalid pinned rust-toolchain.toml") from error
    if not isinstance(channel, str) or not re.fullmatch(r"\d+\.\d+\.\d+", channel):
        raise ValueError("rust-toolchain channel must be an exact stable version")
    return channel


def prepare_isolated_cargo_home(path: Path) -> None:
    path.mkdir()
    real_home = Path(pwd.getpwuid(os.getuid()).pw_dir) / ".cargo"
    for name in ("registry", "git"):
        source = real_home / name
        if source.is_dir():
            (path / name).symlink_to(source, target_is_directory=True)


def test_environment(
    target_dir: Path,
    cargo_home: Path,
    temporary_dir: Path,
    toolchain: TrustedToolchain,
) -> dict[str, str]:
    home = Path(pwd.getpwuid(os.getuid()).pw_dir)
    return {
        "CARGO_HOME": str(cargo_home),
        "CARGO_INCREMENTAL": "0",
        "CARGO_TARGET_DIR": str(target_dir),
        "CARGO_TERM_COLOR": "never",
        "HOME": str(home),
        "LANG": "C",
        "LC_ALL": "C",
        "PATH": trusted_path(),
        "RUST_BACKTRACE": "0",
        "RUSTC": str(toolchain.rustc),
        "RUSTDOC": str(toolchain.rustdoc),
        "RUSTUP_HOME": str(home / ".rustup"),
        "TMPDIR": str(temporary_dir),
        "TZ": "UTC",
    }


def terminate_process(process: subprocess.Popen[str]) -> str:
    os.killpg(process.pid, signal.SIGTERM)
    try:
        output, _ = process.communicate(timeout=5)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        output, _ = process.communicate()
    return output


def discover_test_executable(output: str, case: Case, target_dir: Path) -> Path:
    expected_name = case.package.replace("-", "_") if case.target == "lib" else case.target
    expected_kind = "lib" if case.target == "lib" else "test"
    candidates: set[Path] = set()
    for line in output.splitlines():
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if (
            not isinstance(message, dict)
            or message.get("reason") != "compiler-artifact"
            or not isinstance(message.get("profile"), dict)
            or message["profile"].get("test") is not True
            or not isinstance(message.get("target"), dict)
            or message["target"].get("name") != expected_name
            or expected_kind not in message["target"].get("kind", [])
            or not isinstance(message.get("executable"), str)
        ):
            continue
        try:
            executable = Path(message["executable"]).resolve(strict=True)
            executable.relative_to(target_dir.resolve(strict=True))
        except (OSError, ValueError) as error:
            raise ValueError(f"test executable escapes the isolated target directory for {case.case_id}") from error
        if executable.is_symlink() or not executable.is_file() or not os.access(executable, os.X_OK):
            raise ValueError(f"test executable is not a regular executable for {case.case_id}")
        candidates.add(executable)
    if len(candidates) != 1:
        raise ValueError(f"cargo must report exactly one test executable for {case.case_id}")
    return candidates.pop()


def failed_case(failure_code: str, executable_sha256: str | None = None) -> dict[str, Any]:
    return {
        "executableSha256": executable_sha256,
        "failed": 1,
        "failureCode": failure_code,
        "ignored": 0,
        "measured": 0,
        "measurement": None,
        "passed": 0,
        "result": "FAIL",
    }


def run_case(
    toolchain: TrustedToolchain,
    rust_root: Path,
    target_dir: Path,
    cargo_home: Path,
    temporary_dir: Path,
    case: Case,
) -> tuple[dict[str, Any], str]:
    environment = test_environment(target_dir, cargo_home, temporary_dir, toolchain)
    build = subprocess.Popen(
        [str(toolchain.cargo), *case.cargo_compile_args()],
        cwd=rust_root,
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        start_new_session=True,
    )
    try:
        build_output, _ = build.communicate(timeout=CASE_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        return failed_case("TIMEOUT"), terminate_process(build)
    if build.returncode != 0:
        return failed_case("BUILD_FAILED"), build_output
    try:
        executable = discover_test_executable(build_output, case, target_dir)
    except ValueError:
        return failed_case("EXECUTABLE_INVALID"), build_output
    executable_sha256 = sha256_bytes(executable.read_bytes())

    process = subprocess.Popen(
        [str(executable), *case.executable_args()],
        cwd=rust_root,
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        start_new_session=True,
    )
    try:
        output, _ = process.communicate(timeout=CASE_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired:
        return failed_case("TIMEOUT", executable_sha256), terminate_process(process)

    if sha256_bytes(executable.read_bytes()) != executable_sha256:
        return failed_case("EXECUTABLE_INVALID"), output

    try:
        counts = parse_test_counts(output)
    except ValueError:
        return failed_case("SUMMARY_INVALID", executable_sha256), output
    measurement: dict[str, Any] | None = None
    try:
        measurement = parse_measurement(output, case)
    except ValueError:
        if process.returncode == 0 and counts == (1, 0, 0, 0):
            return failed_case("MEASUREMENT_INVALID", executable_sha256), output
    if process.returncode == 0 and counts == (1, 0, 0, 0) and measurement is not None:
        return {
            "executableSha256": executable_sha256,
            "failed": 0,
            "failureCode": "NONE",
            "ignored": 0,
            "measured": 0,
            "measurement": measurement,
            "passed": 1,
            "result": "PASS",
        }, output
    return {
        "executableSha256": executable_sha256,
        "failed": max(1, counts[1]),
        "failureCode": "COMMAND_FAILED",
        "ignored": 0,
        "measured": 0,
        "measurement": measurement,
        "passed": 0,
        "result": "FAIL",
    }, output


def run_evidence(output_dir: Path, *, repo: Path = ROOT) -> Path:
    reject_environment_overrides(os.environ)
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
    toolchain_bytes = source_blob(repo, source_sha, TOOLCHAIN_RELATIVE)
    cargo_config_bytes = source_blob(repo, source_sha, CARGO_CONFIG_RELATIVE)
    toolchain_path = repo / TOOLCHAIN_RELATIVE
    cargo_config_path = repo / CARGO_CONFIG_RELATIVE
    if toolchain_path.read_bytes() != toolchain_bytes or cargo_config_path.read_bytes() != cargo_config_bytes:
        raise ValueError("Rust toolchain inputs must match their exact source commit blobs")
    validate_cargo_config(cargo_config_path)
    channel = read_toolchain_channel(toolchain_path)
    toolchain = resolve_trusted_toolchain(channel)
    environment = {
        "architecture": platform.machine().lower(),
        "cargoExecutableSha256": sha256_bytes(toolchain.cargo.read_bytes()),
        "cargoVersion": toolchain.cargo_version,
        "operatingSystem": platform.system().lower(),
        "rustcExecutableSha256": sha256_bytes(toolchain.rustc.read_bytes()),
        "rustcVersion": toolchain.rustc_version,
        "rustdocExecutableSha256": sha256_bytes(toolchain.rustdoc.read_bytes()),
        "rustupExecutableSha256": sha256_bytes(toolchain.rustup.read_bytes()),
        "toolchainChannel": toolchain.channel,
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
                str(trusted_git_path()),
                "-C",
                str(repo),
                "archive",
                "--format=tar",
                "-o",
                str(archive_path),
                source_sha,
            ],
            check=True,
            env={
                "HOME": str(Path(pwd.getpwuid(os.getuid()).pw_dir)),
                "LANG": "C",
                "LC_ALL": "C",
                "PATH": trusted_path(),
                "TZ": "UTC",
            },
        )
        with tarfile.open(archive_path, "r") as archive:
            archive.extractall(snapshot_root)
        snapshot_configs = sorted(
            path.relative_to(snapshot_root)
            for path in snapshot_root.rglob("config*")
            if path.parent.name == ".cargo" and path.name in {"config", "config.toml"}
        )
        if snapshot_configs != [CARGO_CONFIG_RELATIVE]:
            raise ValueError("source snapshot must contain only the allowlisted Cargo config")
        snapshot_cargo_config = snapshot_root / CARGO_CONFIG_RELATIVE
        if snapshot_cargo_config.read_bytes() != cargo_config_bytes:
            raise ValueError("snapshot Cargo config does not match exact source")
        validate_cargo_config(snapshot_cargo_config)
        target_dir = temporary_root / "target"
        target_dir.mkdir()
        cargo_home = temporary_root / "cargo-home"
        prepare_isolated_cargo_home(cargo_home)
        run_tmp = temporary_root / "tmp"
        run_tmp.mkdir()
        for case in REQUIRED_CASES:
            result, _discarded_raw_output = run_case(
                toolchain,
                snapshot_root / "native/rust",
                target_dir,
                cargo_home,
                run_tmp,
                case,
            )
            artifact = {
                "caseId": case.case_id,
                **result,
                "sourceSha": source_sha,
                "testName": case.test_name,
                "toolchainChannel": toolchain.channel,
                "version": ARTIFACT_VERSION,
            }
            artifact_path = artifact_dir / f"{case.case_id}.json"
            artifact_path.write_bytes(canonical_json_bytes(artifact))
            rows.append(
                {
                    "artifact": artifact_path.name,
                    "artifactSha256": sha256_bytes(artifact_path.read_bytes()),
                    "executableSha256": result["executableSha256"],
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
            "cargoConfigSha256": sha256_bytes(cargo_config_bytes),
            "cargoLockSha256": sha256_bytes(lock_bytes),
            "runnerSha256": sha256_bytes(runner_bytes),
            "snapshotMethod": "git-archive",
            "suiteDefinitionSha256": suite_definition_sha256(),
            "toolchainFileSha256": sha256_bytes(toolchain_bytes),
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
