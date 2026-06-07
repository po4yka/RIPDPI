#!/usr/bin/env python3
"""Validate REALITY BoringSSL hook vector metadata against workspace pins."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CARGO_TOML = ROOT / "native/rust/Cargo.toml"
VECTOR_PATH = ROOT / "native/rust/crates/ripdpi-vless/tests/reality_hook_vector.json"
PINNED_DEPS = ("boring", "tokio-boring")


def load_workspace_versions(path: Path = CARGO_TOML) -> dict[str, str]:
    text = path.read_text(encoding="utf-8")
    versions: dict[str, str] = {}
    for dep in PINNED_DEPS:
        match = re.search(rf"(?m)^{re.escape(dep)}\s*=\s*\"([^\"]+)\"\s*$", text)
        if not match:
            raise ValueError(f"missing workspace dependency pin for {dep}")
        version = match.group(1)
        if not version.startswith("="):
            raise ValueError(f"{dep} must use an exact Cargo pin, got {version!r}")
        versions[dep] = version
    return versions


def load_vector_versions(path: Path = VECTOR_PATH) -> dict[str, str]:
    data = json.loads(path.read_text(encoding="utf-8"))
    versions: dict[str, str] = {}
    for dep in PINNED_DEPS:
        value = data.get(dep)
        if not isinstance(value, str) or not value:
            raise ValueError(f"REALITY hook vector is missing {dep}")
        versions[dep] = value
    if data.get("fixedNowSecs") != 1_700_000_000:
        raise ValueError("REALITY hook vector fixedNowSecs drifted from the hook test")
    if data.get("shortIdHex") != "abcd":
        raise ValueError("REALITY hook vector shortIdHex drifted from the hook test")
    return versions


def validate(cargo_toml: Path = CARGO_TOML, vector_path: Path = VECTOR_PATH) -> dict[str, str]:
    workspace = load_workspace_versions(cargo_toml)
    vector = load_vector_versions(vector_path)
    mismatches = {dep: (workspace[dep], vector[dep]) for dep in PINNED_DEPS if workspace[dep] != vector[dep]}
    if mismatches:
        details = ", ".join(f"{dep}: Cargo.toml={cargo} vector={vector}" for dep, (cargo, vector) in mismatches.items())
        raise ValueError(f"REALITY BoringSSL hook vector must be updated with dependency pin changes: {details}")
    return workspace


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", action="store_true", help="print the validated versions")
    args = parser.parse_args(argv)
    versions = validate()
    if args.report:
        print(json.dumps({"realityHookBoringVersions": versions}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
