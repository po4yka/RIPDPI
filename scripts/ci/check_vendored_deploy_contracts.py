#!/usr/bin/env python3
"""Verify the vendored ripdpi-vpn-deploy bundle and field-report contracts against their pinned manifest.

Without arguments, every vendored file must match its SHA-256 in the manifest and every file the vendored
patterns cover must be listed, so a hand edit or an unlisted copy fails. With --source pointing at the
deployer's contract/ directory, the source set must equal the manifest too, so a fixture the deployer added
or changed fails until it is re-vendored with --update.
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _common import REPO_ROOT

CONTRACT_DIR = Path("core/data/src/test/resources/contract")
MANIFEST_NAME = "vendored-deploy-contracts.sha256"
VENDORED_PATTERNS = (
    "ripdpi-bundle.*.json",
    "client-field-report.*.json",
    "client-network-hint.*.json",
    "negative/neg-*.json",
    "negative/report-*.json",
    "negative/hint-*.json",
    "negative/README.md",
)
SOURCE_PREFIX = "# source: "


def vendored_files(directory: Path) -> list[str]:
    return sorted({path.relative_to(directory).as_posix() for pattern in VENDORED_PATTERNS for path in directory.glob(pattern)})


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_manifest(path: Path) -> dict[str, str]:
    entries: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        digest, name = line.split("  ", 1)
        entries[name] = digest
    return entries


def compare(directory: Path, manifest: dict[str, str], label: str) -> list[str]:
    failures: list[str] = []
    present = vendored_files(directory)
    for name in sorted(set(manifest) - set(present)):
        failures.append(f"{label}: {name} is pinned but missing")
    for name in sorted(set(present) - set(manifest)):
        failures.append(f"{label}: {name} is not pinned in {MANIFEST_NAME}")
    for name in sorted(set(present) & set(manifest)):
        if sha256(directory / name) != manifest[name]:
            failures.append(f"{label}: {name} differs from its pinned SHA-256")
    return failures


def check(repo_root: Path = REPO_ROOT, source: Path | None = None) -> list[str]:
    contract_dir = repo_root / CONTRACT_DIR
    manifest_path = contract_dir / MANIFEST_NAME
    if not manifest_path.is_file():
        return [f"missing {CONTRACT_DIR / MANIFEST_NAME}"]
    manifest = read_manifest(manifest_path)
    failures = compare(contract_dir, manifest, "vendored")
    if source is not None:
        failures.extend(compare(source, manifest, "deployer"))
    return failures


def update(source: Path, source_revision: str, repo_root: Path = REPO_ROOT) -> None:
    contract_dir = repo_root / CONTRACT_DIR
    for name in vendored_files(contract_dir):
        (contract_dir / name).unlink()
    names = vendored_files(source)
    for name in names:
        target = contract_dir / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / name, target)
    lines = [f"{SOURCE_PREFIX}ripdpi-vpn-deploy contract/ at {source_revision}"]
    lines.extend(f"{sha256(contract_dir / name)}  {name}" for name in names)
    (contract_dir / MANIFEST_NAME).write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--source", type=Path, help="the deployer's contract/ directory")
    parser.add_argument("--update", metavar="REVISION", help="re-vendor from --source and pin it as REVISION")
    args = parser.parse_args()
    if args.update:
        if args.source is None:
            parser.error("--update requires --source")
        update(args.source, args.update)
    failures = check(source=args.source)
    for failure in failures:
        print(f"error: {failure}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
