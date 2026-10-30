#!/usr/bin/env python3
"""Fetch the pinned upstream NaiveProxy client (libnaive.so) for each ABI.

Downloads the release APK, verifies its sha256, extracts only
lib/<abi>/libnaive.so, verifies the ELF sha256 and writes it to
<output-root>/<abi>/libnaive.so with mode 0755. Builds never download;
Gradle only verifies what this script produced.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MANIFEST = REPO_ROOT / "native/naiveproxy-upstream/sources.json"
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "native/naiveproxy-upstream/artifacts"
MAX_ELF_BYTES = 256 * 1024 * 1024


class FetchError(Exception):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, target: Path) -> None:
    if not url.startswith("https://"):
        raise FetchError(f"refusing non-HTTPS URL: {url}")
    try:
        with urllib.request.urlopen(url, timeout=120) as response, target.open("wb") as out:
            while chunk := response.read(1 << 20):
                out.write(chunk)
    except OSError as exc:
        raise FetchError(f"download failed for {url}: {exc}") from exc


def extract_member(apk: Path, abi: str, target: Path) -> None:
    member = f"lib/{abi}/libnaive.so"
    with zipfile.ZipFile(apk) as archive:
        for info in archive.infolist():
            name = info.filename
            parts = name.split("/")
            unix_mode = info.external_attr >> 16
            if name.startswith("/") or ".." in parts or "\\" in name:
                raise FetchError(f"unsafe member path in {apk.name}: {name!r}")
            if stat.S_ISLNK(unix_mode):
                raise FetchError(f"symlink member in {apk.name}: {name!r}")
        try:
            info = archive.getinfo(member)
        except KeyError:
            raise FetchError(f"{apk.name} has no member {member}") from None
        if info.is_dir() or info.file_size > MAX_ELF_BYTES:
            raise FetchError(f"unexpected member {member} in {apk.name}")
        with archive.open(info) as src, target.open("wb") as out:
            written = 0
            while chunk := src.read(1 << 20):
                written += len(chunk)
                if written > MAX_ELF_BYTES:
                    raise FetchError(f"member {member} exceeds the size limit")
                out.write(chunk)


def fetch_abi(abi: str, entry: dict, output_root: Path) -> str:
    target = output_root / abi / "libnaive.so"
    expected_elf = entry["libnaiveSha256"]
    if target.is_file() and sha256_file(target) == expected_elf:
        target.chmod(0o755)
        return "cached"
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="naive-fetch-") as tmp:
        apk = Path(tmp) / entry["asset"]
        download(entry["url"], apk)
        actual_apk = sha256_file(apk)
        if actual_apk != entry["apkSha256"]:
            raise FetchError(
                f"{abi}: APK sha256 mismatch for {entry['asset']}: "
                f"expected {entry['apkSha256']}, got {actual_apk}"
            )
        staged = Path(tmp) / "libnaive.so"
        extract_member(apk, abi, staged)
        actual_elf = sha256_file(staged)
        if actual_elf != expected_elf:
            raise FetchError(f"{abi}: libnaive.so sha256 mismatch: expected {expected_elf}, got {actual_elf}")
        staged.chmod(0o755)
        fd, partial = tempfile.mkstemp(dir=target.parent, prefix=".libnaive-")
        os.close(fd)
        try:
            Path(partial).write_bytes(staged.read_bytes())
            os.chmod(partial, 0o755)
            os.replace(partial, target)
        finally:
            if os.path.exists(partial):
                os.unlink(partial)
    return "fetched"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--abi", action="append", help="ABI to fetch; repeatable (default: all in the manifest)")
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    args = parser.parse_args(argv)

    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1:
        print(f"error: unsupported sources.json schemaVersion {manifest.get('schemaVersion')!r}", file=sys.stderr)
        return 1
    entries = manifest["abis"]
    abis = args.abi or list(entries)
    unknown = [abi for abi in abis if abi not in entries]
    if unknown:
        print(f"error: ABI not in {args.manifest.name}: {', '.join(unknown)}", file=sys.stderr)
        return 1
    for abi in abis:
        try:
            state = fetch_abi(abi, entries[abi], args.output_root)
        except FetchError as exc:
            print(f"error: {exc}", file=sys.stderr)
            return 1
        print(f"{abi}: {state} {args.output_root / abi / 'libnaive.so'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
