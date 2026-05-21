#!/usr/bin/env python3
"""Verify protocol wire fixtures are tagged by the pinned upstream version."""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


@dataclass(frozen=True)
class ProtocolFixtureSpec:
    name: str
    fixture_dir: Path
    spec_version: Path


PROTOCOL_FIXTURES = [
    ProtocolFixtureSpec(
        name="hysteria2",
        fixture_dir=Path("contract-fixtures/hysteria2"),
        spec_version=Path("native/rust/crates/ripdpi-hysteria2/SPEC_VERSION.md"),
    ),
    ProtocolFixtureSpec(
        name="vless",
        fixture_dir=Path("contract-fixtures/vless"),
        spec_version=Path("native/rust/crates/ripdpi-vless/SPEC_VERSION.md"),
    ),
]


def pinned_upstream_tag(spec_text: str) -> str | None:
    for line in spec_text.splitlines():
        prefix = "- **Upstream tag:**"
        if line.startswith(prefix):
            value = line.removeprefix(prefix).strip()
            return value.split()[0] if value else None
    return None


def check_protocol_fixture_versions(repo_root: Path = REPO_ROOT) -> list[str]:
    failures: list[str] = []
    for spec in PROTOCOL_FIXTURES:
        fixture_dir = repo_root / spec.fixture_dir
        spec_version = repo_root / spec.spec_version
        failures.extend(check_protocol_fixture(repo_root, spec, fixture_dir, spec_version))
    return failures


def check_protocol_fixture(
    repo_root: Path,
    spec: ProtocolFixtureSpec,
    fixture_dir: Path,
    spec_version: Path,
) -> list[str]:
    failures: list[str] = []
    if not spec_version.exists():
        return [f"{spec.name}: missing {spec.spec_version}"]
    expected_tag = pinned_upstream_tag(spec_version.read_text(encoding="utf-8"))
    if expected_tag is None:
        return [f"{spec.name}: missing upstream tag in {spec.spec_version}"]
    if not fixture_dir.exists():
        return [f"{spec.name}: missing fixture directory {spec.fixture_dir}"]

    tag_dirs: list[Path] = []
    for child in fixture_dir.iterdir():
        if child.name == "README.md" and child.is_file():
            continue
        if child.is_dir():
            tag_dirs.append(child)
        else:
            failures.append(f"{spec.name}: unexpected file {child.relative_to(repo_root)}")

    unexpected_tags = sorted(path.name for path in tag_dirs if path.name != expected_tag)
    for tag in unexpected_tags:
        failures.append(f"{spec.name}: stale or unpinned fixture tag '{tag}', expected only '{expected_tag}'")

    current_tag_dir = fixture_dir / expected_tag
    if not current_tag_dir.is_dir():
        failures.append(f"{spec.name}: missing pinned fixture tag directory {current_tag_dir.relative_to(repo_root)}")
    elif not any(current_tag_dir.rglob("*.bin")):
        failures.append(f"{spec.name}: pinned fixture tag {expected_tag} has no .bin fixtures")

    return failures


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args(argv[1:])

    failures = check_protocol_fixture_versions()
    if failures:
        print("Protocol fixture version check FAILED:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1

    print(f"OK: {len(PROTOCOL_FIXTURES)} protocol fixture roots match pinned upstream tags")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
