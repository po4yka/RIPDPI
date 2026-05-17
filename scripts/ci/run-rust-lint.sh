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

echo "==> FFI panic-boundary scanner self-tests"
# Run the scanner's unit tests first so a regex regression surfaces here
# (cheap, ~0.5s) rather than as a confusing production-scan false positive
# below. Mirrors the discipline applied to other CI guards.
python3 -m unittest discover -s "$repo_root/scripts/ci/tests" -p 'test_check_ffi_panic_boundary.py' -v

echo "==> FFI panic-boundary guard"
# Policy: docs/rust-soundness-policy.md -- "FFI panic-unwind containment".
# Allowlist: ci/ffi-panic-boundary-allowlist.toml.
python3 "$repo_root/scripts/ci/check_ffi_panic_boundary.py"

echo "==> drop-order scanner self-tests"
# Field declaration order is part of every multi-resource Drop impl's
# safety contract. The scanner enforces a `Drop order:` marker comment
# (or allowlist entry) for every struct that has `impl Drop` and 2+
# resource-bearing fields. Self-tests first so regex/balancer
# regressions surface here.
python3 -m unittest discover -s "$repo_root/scripts/ci/tests" -p 'test_check_drop_order.py' -v

echo "==> drop-order guard"
# Policy: docs/rust-soundness-policy.md -- "Field declaration order in
# Drop impls". Allowlist: ci/drop-order-allowlist.toml.
python3 "$repo_root/scripts/ci/check_drop_order.py"

echo "==> clippy"
cargo clippy --manifest-path "$workspace_manifest" --workspace --all-targets -- -D warnings

echo "==> rustdoc"
# `cargo doc` is run without `-D warnings` because the workspace has a small
# tail of pre-existing intra-doc-link warnings in legacy crates; turning them
# into errors would block this guard from landing. The rustdoc build still
# fails on real compile errors and on `#[doc(deny(...))]` directives, so it
# is meaningful as a CI gate even at the default warn level.
cargo doc --manifest-path "$workspace_manifest" --workspace --all-features --no-deps
