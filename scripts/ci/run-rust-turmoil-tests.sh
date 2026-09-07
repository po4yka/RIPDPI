#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"

NEXTEST_PROFILE="${CI:+ci}"
NEXTEST_ARGS=(${NEXTEST_PROFILE:+--profile "$NEXTEST_PROFILE"})

# Ordinary unit tests belong to the workspace lane. This lane owns the
# simulated-network unit tests and the in-process TUN integration binary.
echo "==> turmoil and in-process TUN tests"
if bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest --version >/dev/null 2>&1; then
    bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked \
        --manifest-path "$workspace_manifest" -p ripdpi-tunnel-core -p ripdpi-dns-resolver \
        -E 'binary(=tun_e2e) or test(turmoil_)' "${NEXTEST_ARGS[@]}"
else
    cargo test --locked --manifest-path "$workspace_manifest" \
        -p ripdpi-tunnel-core -p ripdpi-dns-resolver --lib turmoil_ -- --nocapture
    cargo test --locked --manifest-path "$workspace_manifest" \
        -p ripdpi-tunnel-core --test tun_e2e -- --nocapture
fi
