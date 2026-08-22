#!/usr/bin/env bash
set -euo pipefail

# Run a Cargo command through the machine's heavy-build gate when one is
# present.
#
# Some developer machines wrap `cargo` with a guard that rejects external
# subcommands such as `clippy`, `deny`, and `nextest`, because aliases and
# external subcommands could inject unchecked compiler options after the
# wrapper's validation. That guard's sanctioned escape hatch is to execute the
# real Cargo binary under build-gate, which preserves the machine-wide
# heavy-build slot serialization. On machines without the guard -- including
# CI runners -- this script degrades to plain `cargo`.
#
# Usage: cargo-guarded.sh cargo <subcommand> [arguments...]
#
# The literal leading `cargo` argument is required so scanners such as
# scripts/ci/check_harness_cargo_locked.py keep seeing every harness Cargo
# command (and its `--locked`) in plain text.
#
# Example:
#   cargo-guarded.sh cargo clippy --locked --workspace --all-targets

if [ "${1:-}" != "cargo" ]; then
    echo "usage: $0 cargo <subcommand> [arguments...]" >&2
    exit 64
fi
shift

real_cargo="${BUILD_GATE_REAL_CARGO:-${HOME}/.cargo/bin/cargo}"
if [ "${BUILD_GATE_DISABLED:-0}" != "1" ] && command -v build-gate >/dev/null 2>&1 && [ -x "${real_cargo}" ]; then
    exec build-gate -- "${real_cargo}" "$@"
fi
exec cargo "$@"
