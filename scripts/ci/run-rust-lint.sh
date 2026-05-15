#!/usr/bin/env bash
set -euo pipefail

# Rust formatting and lint checks (fmt + clippy + custom guards).
# Split from run-rust-native-checks.sh for parallel CI execution.

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"

echo "==> rustfmt"
cargo fmt --manifest-path "$workspace_manifest" --all --check

echo "==> runtime crate boundaries"
python3 "$repo_root/scripts/ci/check_runtime_crate_boundaries.py"

echo "==> unsafe-boundary allowlist guard"
python3 "$repo_root/scripts/ci/check_unsafe_boundaries.py"

echo "==> clippy"
cargo clippy --manifest-path "$workspace_manifest" --workspace --all-targets -- -D warnings

echo "==> rustdoc"
# `cargo doc` is run without `-D warnings` because the workspace has a small
# tail of pre-existing intra-doc-link warnings in legacy crates; turning them
# into errors would block this guard from landing. The rustdoc build still
# fails on real compile errors and on `#[doc(deny(...))]` directives, so it
# is meaningful as a CI gate even at the default warn level.
cargo doc --manifest-path "$workspace_manifest" --workspace --all-features --no-deps
