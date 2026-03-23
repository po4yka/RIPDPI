#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"

workspace_manifest="$repo_root/native/rust/Cargo.toml"

NEXTEST_PROFILE="${CI:+ci}"
NEXTEST_ARGS=(${NEXTEST_PROFILE:+--profile "$NEXTEST_PROFILE"})

if cargo nextest --version >/dev/null 2>&1; then
    run_fixture_tests() {
        cargo nextest run --manifest-path "$workspace_manifest" -p local-network-fixture "${NEXTEST_ARGS[@]}"
    }

    run_proxy_e2e() {
        cargo nextest run --manifest-path "$workspace_manifest" -p ripdpi-runtime --test network_e2e --no-capture "${NEXTEST_ARGS[@]}"
    }

else
    run_fixture_tests() {
        cargo test --manifest-path "$workspace_manifest" -p local-network-fixture -- --nocapture
    }

    run_proxy_e2e() {
        cargo test --manifest-path "$workspace_manifest" -p ripdpi-runtime --test network_e2e -- --nocapture
    }

fi

echo "==> local network fixture crate"
run_fixture_tests

echo "==> repo-owned proxy runtime E2E"
run_proxy_e2e
