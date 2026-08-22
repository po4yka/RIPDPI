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

echo "==> soundness-audit (issues 35-49) scanner self-tests"
# The six new scanners covering audit issues 35-49 share unit + integration
# tests in a single suite. Run them first so any regex regression surfaces
# here, not as a confusing production-scan false positive.
python3 -m unittest discover -s "$repo_root/scripts/ci/tests" \
    -p 'test_check_soundness_35_49.py' -v

echo "==> packed-struct guard (issue 49)"
# Policy: docs/rust-soundness-policy.md -- "repr(packed) discipline".
# Allowlist: ci/packed-allowlist.toml.
python3 "$repo_root/scripts/ci/check_packed_structs.py"

echo "==> pin / self-reference guard (issues 35-36)"
# Policy: docs/rust-soundness-policy.md -- "Self-referential structs and
# Pin" and "Incorrect Pin API". Allowlist: ci/pin-allowlist.toml.
python3 "$repo_root/scripts/ci/check_pin_and_self_reference.py"

echo "==> raw-slice / layout-arithmetic guard (issues 47-48)"
# Policy: docs/rust-soundness-policy.md -- "Raw slice construction is a
# soundness boundary" and "Allocation / layout arithmetic".
# Allowlist: ci/raw-slice-layout-allowlist.toml.
python3 "$repo_root/scripts/ci/check_raw_slice_and_layout.py"

echo "==> async-safety guard (issues 38-39)"
# Policy: docs/rust-soundness-policy.md -- "Blocking inside async runtime".
# Complementary to clippy's `await_holding_lock` (issue 37/42) which is
# enforced via [workspace.lints.clippy] in native/rust/Cargo.toml.
# Allowlist: ci/async-safety-allowlist.toml.
python3 "$repo_root/scripts/ci/check_async_safety.py"

echo "==> locking / shared-state guard (issues 40-44)"
# Policy: docs/rust-soundness-policy.md -- "Rc<RefCell<T>> as implicit
# mutable graph" and "Rc / Arc cycles". Complementary to clippy's
# `rc_mutex` and `arc_with_non_send_sync`.
# Allowlist: ci/locking-allowlist.toml.
python3 "$repo_root/scripts/ci/check_locking_and_shared_state.py"

echo "==> PhantomData / variance guard (issues 45-46)"
# Policy: docs/rust-soundness-policy.md -- "PhantomData and variance".
# Allowlist: ci/phantomdata-variance-allowlist.toml.
python3 "$repo_root/scripts/ci/check_phantomdata_variance.py"

echo "==> clippy"
bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo clippy --locked --manifest-path "$workspace_manifest" --workspace --all-targets -- -D warnings

echo "==> rustdoc"
# `cargo doc --locked` is run without `-D warnings` because the workspace has a small
# tail of pre-existing intra-doc-link warnings in legacy crates; turning them
# into errors would block this guard from landing. The rustdoc build still
# fails on real compile errors and on `#[doc(deny(...))]` directives, so it
# is meaningful as a CI gate even at the default warn level.
cargo doc --locked --manifest-path "$workspace_manifest" --workspace --all-features --no-deps
