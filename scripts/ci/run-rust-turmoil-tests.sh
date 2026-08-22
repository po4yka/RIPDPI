#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"

NEXTEST_PROFILE="${CI:+ci}"
NEXTEST_ARGS=(${NEXTEST_PROFILE:+--profile "$NEXTEST_PROFILE"})

if bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest --version >/dev/null 2>&1; then
    run_tunnel_tests() {
        bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked --manifest-path "$workspace_manifest" -p ripdpi-tunnel-core --no-capture "${NEXTEST_ARGS[@]}"
    }

    run_resolver_tests() {
        bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked --manifest-path "$workspace_manifest" -p ripdpi-dns-resolver --no-capture "${NEXTEST_ARGS[@]}"
    }
else
    run_tunnel_tests() {
        cargo test --locked --manifest-path "$workspace_manifest" -p ripdpi-tunnel-core -- --nocapture
    }

    run_resolver_tests() {
        cargo test --locked --manifest-path "$workspace_manifest" -p ripdpi-dns-resolver -- --nocapture
    }
fi

echo "==> turmoil-backed tunnel tests"
run_tunnel_tests

echo "==> turmoil-backed resolver tests"
run_resolver_tests
