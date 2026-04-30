#!/usr/bin/env bash
set -euo pipefail

# Rust formatting and lint checks (fmt + clippy).
# Split from run-rust-native-checks.sh for parallel CI execution.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"

echo "==> rustfmt"
cargo fmt --manifest-path "$workspace_manifest" --all --check

echo "==> runtime crate boundaries"
python3 "$repo_root/scripts/ci/check_runtime_crate_boundaries.py"

echo "==> clippy"
cargo clippy --manifest-path "$workspace_manifest" --workspace --all-targets -- -D warnings
