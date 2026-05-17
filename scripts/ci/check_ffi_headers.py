#!/usr/bin/env python3
"""
check_ffi_headers.py — CI guard for FFI header surface (issue #25).

POLICY
------
The RIPDPI workspace does NOT use bindgen / cbindgen / autocxx / any other
auto-generated FFI binding tool. All extern blocks and extern fn definitions
are hand-written and reviewed (issues #24 and #25 audits confirmed zero
production violations).

This guard enforces three properties that together constitute the workspace's
"header check" discipline:

1. No `bindgen`, `cbindgen`, `autocxx`, `safer-ffi`, `cxx` crate listed in any
   `Cargo.toml` under `native/rust/`. New adoption must include a checked-in
   binding snapshot AND a drift-detection CI step (per docs/rust-soundness-
   policy.md § "FFI layout and ABI" — "bindgen invocation" scanner pattern).

2. No `.h` / `.hpp` / `.hxx` header files committed under `native/rust/`
   except inside `vendor/` (BoringSSL vendored sources). Any other `.h`
   indicates either auto-generated bindings (forbidden per #1) or hand-
   written headers that need separate review for ABI compatibility.

3. No `*.bindings.rs` / `bindgen_bindings.rs` / similar auto-generated Rust
   file names committed under `native/rust/`. Such files are the typical
   output of bindgen runs and should never be checked in without the
   per-#1 review step.

EXIT CODES
----------
  0  No violations.
  1  At least one violation; fix per the printed remediation list.
"""

from __future__ import annotations

import re
import sys
import tomllib
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
RUST_ROOT = REPO_ROOT / "native" / "rust"
CRATES_ROOT = RUST_ROOT / "crates"

FORBIDDEN_FFI_CRATES = {
    "bindgen",
    "cbindgen",
    "autocxx",
    "safer-ffi",
    "cxx",
    "cxx-build",
    "cxx-gen",
}

EXCLUDED_DIRS_FOR_HEADERS = {"vendor", "target"}

AUTO_GENERATED_RUST_NAMES = re.compile(
    r"(?:^|/)(?:bindings|bindgen_bindings|generated|auto_bindings)\.rs$"
)


def find_forbidden_ffi_deps() -> list[tuple[Path, str]]:
    """Scan every Cargo.toml under native/rust/ for forbidden FFI deps."""
    out: list[tuple[Path, str]] = []
    for cargo in RUST_ROOT.rglob("Cargo.toml"):
        if "vendor" in cargo.parts:
            continue
        try:
            with cargo.open("rb") as fh:
                data = tomllib.load(fh)
        except (OSError, tomllib.TOMLDecodeError):
            continue
        for section in ("dependencies", "dev-dependencies", "build-dependencies"):
            deps = data.get(section, {})
            for name in deps:
                if name in FORBIDDEN_FFI_CRATES:
                    out.append((cargo.relative_to(REPO_ROOT), name))
        for target_block in data.get("target", {}).values():
            for section in ("dependencies", "dev-dependencies", "build-dependencies"):
                deps = target_block.get(section, {})
                for name in deps:
                    if name in FORBIDDEN_FFI_CRATES:
                        out.append((cargo.relative_to(REPO_ROOT), name))
    return out


def find_unreviewed_c_headers() -> list[Path]:
    """Scan for .h / .hpp / .hxx files outside the vendor directory."""
    out: list[Path] = []
    for header in RUST_ROOT.rglob("*.h"):
        if any(d in header.parts for d in EXCLUDED_DIRS_FOR_HEADERS):
            continue
        out.append(header.relative_to(REPO_ROOT))
    for ext in ("*.hpp", "*.hxx"):
        for header in RUST_ROOT.rglob(ext):
            if any(d in header.parts for d in EXCLUDED_DIRS_FOR_HEADERS):
                continue
            out.append(header.relative_to(REPO_ROOT))
    return out


def find_auto_generated_rust_files() -> list[Path]:
    """Scan for Rust files with bindgen-like names."""
    out: list[Path] = []
    for rs in CRATES_ROOT.rglob("*.rs"):
        rel = str(rs.relative_to(REPO_ROOT))
        if AUTO_GENERATED_RUST_NAMES.search(rel):
            out.append(rs.relative_to(REPO_ROOT))
    return out


def main() -> int:
    forbidden_deps = find_forbidden_ffi_deps()
    unreviewed_headers = find_unreviewed_c_headers()
    auto_files = find_auto_generated_rust_files()

    violation_count = len(forbidden_deps) + len(unreviewed_headers) + len(auto_files)

    print(
        f"Scanned native/rust/ for FFI header hygiene "
        f"({violation_count} violation(s) across "
        f"{len(forbidden_deps)} forbidden FFI dep(s), "
        f"{len(unreviewed_headers)} unreviewed C header(s), "
        f"{len(auto_files)} auto-generated Rust file(s))."
    )

    if violation_count == 0:
        print("\nOK: FFI header surface is hand-written and reviewed.")
        return 0

    print()
    if forbidden_deps:
        print(f"FAIL: {len(forbidden_deps)} forbidden FFI binding crate(s):")
        for path, name in forbidden_deps:
            print(f"  {path}  dep={name}")
    if unreviewed_headers:
        print(f"FAIL: {len(unreviewed_headers)} unreviewed C header(s):")
        for path in unreviewed_headers:
            print(f"  {path}")
    if auto_files:
        print(f"FAIL: {len(auto_files)} auto-generated Rust file(s):")
        for path in auto_files:
            print(f"  {path}")
    print(
        "\nTo fix, in order of preference:\n"
        "  1) Revert the addition (the workspace policy is hand-written FFI).\n"
        "  2) If FFI binding generation is genuinely required:\n"
        "     - commit the generated output AS-IF hand-written (no .gitignore),\n"
        "     - add a CI step that re-runs the generator and diffs against\n"
        "       the committed snapshot,\n"
        "     - extend this guard's allowlist with the entry name and the\n"
        "       drift-detection step location,\n"
        "     - update docs/rust-soundness-policy.md § 'FFI layout and ABI'\n"
        "       with the per-binding review process.\n"
        "\nPolicy: docs/rust-soundness-policy.md § 'FFI layout and ABI'"
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
