#!/usr/bin/env python3
"""Normalize release APK names and generate GitHub updater metadata."""

from __future__ import annotations

import argparse
import glob
import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path

ABI_ORDER = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64", "universal")
APK_NAME_PATTERN = re.compile(r"^[A-Za-z0-9._-]+\.apk$")


@dataclass(frozen=True)
class ReleaseApk:
    abi: str
    path: Path
    version_code: int
    version_name: str
    sha256: str
    size_bytes: int

    @property
    def is_universal(self) -> bool:
        return self.abi == "universal"

    def artifact_payload(self) -> dict[str, object]:
        return {
            "abi": self.abi,
            "apkName": self.path.name,
            "versionCode": self.version_code,
            "sha256": self.sha256,
            "sizeBytes": self.size_bytes,
        }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk-glob", required=True)
    parser.add_argument("--output-metadata", required=True)
    parser.add_argument("--gradle-properties", required=True)
    parser.add_argument("--package-name", required=True)
    parser.add_argument("--artifact-prefix", required=True)
    parser.add_argument("--update-json")
    parser.add_argument("--sha256sums")
    parser.add_argument("--size-report")
    parser.add_argument("--max-abi-apk-size-mib", type=float)
    parser.add_argument("--changelog", default="See GitHub release notes.")
    return parser.parse_args()


def matching_apks(pattern: str) -> dict[str, Path]:
    matches = sorted(Path(path) for path in glob.glob(pattern))
    apks = [path for path in matches if path.suffix == ".apk" and "unaligned" not in path.name]
    if not apks:
        raise SystemExit(f"expected at least one APK for {pattern}, found none")
    return {path.name: path for path in apks}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_min_sdk(path: Path) -> int:
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("ripdpi.minSdk="):
            return int(line.split("=", 1)[1])
    raise SystemExit(f"ripdpi.minSdk not found in {path}")


def abi_from_element(element: dict[str, object]) -> str:
    output_type = str(element.get("type", "")).upper()
    if output_type == "UNIVERSAL":
        return "universal"
    filters = element.get("filters", [])
    if isinstance(filters, list):
        for item in filters:
            if not isinstance(item, dict):
                continue
            filter_type = str(item.get("filterType") or item.get("type") or "").upper()
            if filter_type == "ABI":
                value = item.get("value") or item.get("identifier")
                if isinstance(value, str) and value:
                    return value
    if output_type == "SINGLE":
        return "universal"
    raise SystemExit(f"could not determine ABI for output metadata element: {element}")


def stable_apk_name(prefix: str, abi: str) -> str:
    apk_name = f"{prefix}-{abi}.apk"
    if not APK_NAME_PATTERN.fullmatch(apk_name):
        raise SystemExit(f"invalid generated APK name: {apk_name}")
    return apk_name


def rename_if_needed(source: Path, target: Path) -> Path:
    if source == target:
        return source
    if target.exists():
        target.unlink()
    source.rename(target)
    return target


def collect_release_apks(
    apk_glob: str,
    output_metadata: Path,
    artifact_prefix: str,
) -> list[ReleaseApk]:
    apks_by_name = matching_apks(apk_glob)
    metadata = json.loads(output_metadata.read_text(encoding="utf-8"))
    elements = metadata.get("elements", [])
    if not isinstance(elements, list):
        raise SystemExit(f"{output_metadata} does not contain an elements array")

    release_apks: list[ReleaseApk] = []
    seen_abis: set[str] = set()
    for element in elements:
        if not isinstance(element, dict):
            continue
        output_file = element.get("outputFile")
        if not isinstance(output_file, str) or not output_file.endswith(".apk"):
            continue
        abi = abi_from_element(element)
        target_name = stable_apk_name(artifact_prefix, abi)
        source = apks_by_name.get(Path(output_file).name) or apks_by_name.get(target_name)
        if source is None:
            raise SystemExit(f"could not find APK from output metadata: {output_file} or {target_name}")
        if abi in seen_abis:
            raise SystemExit(f"duplicate APK output for ABI {abi}")
        seen_abis.add(abi)
        target = source.with_name(target_name)
        normalized = rename_if_needed(source, target)
        release_apks.append(
            ReleaseApk(
                abi=abi,
                path=normalized,
                version_code=int(element["versionCode"]),
                version_name=str(element["versionName"]),
                sha256=sha256(normalized),
                size_bytes=normalized.stat().st_size,
            ),
        )

    if not release_apks:
        raise SystemExit(f"no APK outputs found in {output_metadata}")
    return sorted(release_apks, key=lambda item: ABI_ORDER.index(item.abi) if item.abi in ABI_ORDER else len(ABI_ORDER))


def write_update_metadata(
    path: Path,
    release_apks: list[ReleaseApk],
    package_name: str,
    min_sdk: int,
    changelog: str,
) -> None:
    universal = next((apk for apk in release_apks if apk.is_universal), None)
    if universal is None:
        raise SystemExit("GitHub updater metadata requires a universal APK fallback")
    metadata = {
        "schemaVersion": 2,
        "packageName": package_name,
        "versionCode": universal.version_code,
        "versionName": universal.version_name,
        "minSdk": min_sdk,
        "apkName": universal.path.name,
        "sha256": universal.sha256,
        "sizeBytes": universal.size_bytes,
        "changelog": changelog,
        "artifacts": [apk.artifact_payload() for apk in release_apks],
    }
    path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def write_sha256sums(path: Path, release_apks: list[ReleaseApk]) -> None:
    lines = [f"{apk.sha256}  {apk.path.as_posix()}" for apk in release_apks]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def render_size_report(release_apks: list[ReleaseApk], max_abi_size_bytes: int | None) -> str:
    lines = ["# Release APK Size Report", "", "| ABI | APK | Size MiB | Budget MiB | Status |", "|---|---|---:|---:|---|"]
    for apk in release_apks:
        budget = "" if apk.is_universal or max_abi_size_bytes is None else f"{max_abi_size_bytes / 1024 / 1024:.1f}"
        status = "universal" if apk.is_universal else "ok"
        if not apk.is_universal and max_abi_size_bytes is not None and apk.size_bytes > max_abi_size_bytes:
            status = "fail"
        lines.append(
            f"| {apk.abi} | `{apk.path.name}` | {apk.size_bytes / 1024 / 1024:.1f} | {budget} | {status} |",
        )
    return "\n".join(lines) + "\n"


def verify_size_budget(release_apks: list[ReleaseApk], max_abi_size_bytes: int | None) -> None:
    if max_abi_size_bytes is None:
        return
    failures = [apk for apk in release_apks if not apk.is_universal and apk.size_bytes > max_abi_size_bytes]
    if failures:
        details = ", ".join(f"{apk.path.name}={apk.size_bytes / 1024 / 1024:.1f} MiB" for apk in failures)
        raise SystemExit(f"ABI APK size budget exceeded ({max_abi_size_bytes / 1024 / 1024:.1f} MiB): {details}")


def main() -> None:
    args = parse_args()
    release_apks = collect_release_apks(args.apk_glob, Path(args.output_metadata), args.artifact_prefix)
    max_abi_size_bytes = None
    if args.max_abi_apk_size_mib is not None:
        max_abi_size_bytes = int(args.max_abi_apk_size_mib * 1024 * 1024)
    verify_size_budget(release_apks, max_abi_size_bytes)
    if args.update_json:
        write_update_metadata(
            Path(args.update_json),
            release_apks,
            package_name=args.package_name,
            min_sdk=load_min_sdk(Path(args.gradle_properties)),
            changelog=args.changelog,
        )
    if args.sha256sums:
        write_sha256sums(Path(args.sha256sums), release_apks)
    if args.size_report:
        Path(args.size_report).write_text(render_size_report(release_apks, max_abi_size_bytes), encoding="utf-8")


if __name__ == "__main__":
    main()
