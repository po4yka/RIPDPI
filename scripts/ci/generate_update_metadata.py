#!/usr/bin/env python3
"""Generate GitHub updater metadata for a release APK."""

from __future__ import annotations

import argparse
import glob
import hashlib
import json
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk-glob", required=True)
    parser.add_argument("--output-metadata", required=True)
    parser.add_argument("--gradle-properties", required=True)
    parser.add_argument("--package-name", required=True)
    parser.add_argument("--update-json", required=True)
    parser.add_argument("--sha256sums", required=True)
    parser.add_argument("--changelog", default="See GitHub release notes.")
    return parser.parse_args()


def single_apk(pattern: str) -> Path:
    matches = sorted(Path(path) for path in glob.glob(pattern))
    matches = [path for path in matches if path.suffix == ".apk" and "unaligned" not in path.name]
    if len(matches) != 1:
        raise SystemExit(f"expected exactly one APK for {pattern}, found {len(matches)}: {matches}")
    return matches[0]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_output_metadata(path: Path, apk: Path) -> tuple[int, str]:
    data = json.loads(path.read_text(encoding="utf-8"))
    elements = data.get("elements", [])
    selected = next((item for item in elements if item.get("outputFile") == apk.name), None)
    selected = selected or (elements[0] if len(elements) == 1 else None)
    if selected is None:
        raise SystemExit(f"could not find {apk.name} in {path}")
    return int(selected["versionCode"]), str(selected["versionName"])


def load_min_sdk(path: Path) -> int:
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("ripdpi.minSdk="):
            return int(line.split("=", 1)[1])
    raise SystemExit(f"ripdpi.minSdk not found in {path}")


def main() -> None:
    args = parse_args()
    apk = single_apk(args.apk_glob)
    version_code, version_name = load_output_metadata(Path(args.output_metadata), apk)
    checksum = sha256(apk)
    metadata = {
        "schemaVersion": 1,
        "packageName": args.package_name,
        "versionCode": version_code,
        "versionName": version_name,
        "minSdk": load_min_sdk(Path(args.gradle_properties)),
        "apkName": apk.name,
        "sha256": checksum,
        "sizeBytes": apk.stat().st_size,
        "changelog": args.changelog,
    }
    Path(args.update_json).write_text(
        json.dumps(metadata, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    Path(args.sha256sums).write_text(
        f"{checksum}  {apk.as_posix()}\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
