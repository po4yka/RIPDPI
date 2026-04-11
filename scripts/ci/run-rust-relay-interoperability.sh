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

  run_relay_unit_tests() {
    cargo nextest run --manifest-path "$workspace_manifest" -p ripdpi-relay-core -p ripdpi-tuic "${NEXTEST_ARGS[@]}"
  }

  run_runtime_relay_e2e() {
    RIPDPI_RUN_NESTED_PROXY_E2E=1 \
      cargo nextest run --manifest-path "$workspace_manifest" -p ripdpi-runtime --test network_e2e --no-capture "${NEXTEST_ARGS[@]}"
  }
else
  run_fixture_tests() {
    cargo test --manifest-path "$workspace_manifest" -p local-network-fixture -- --nocapture
  }

  run_relay_unit_tests() {
    cargo test --manifest-path "$workspace_manifest" -p ripdpi-relay-core -p ripdpi-tuic -- --nocapture
  }

  run_runtime_relay_e2e() {
    RIPDPI_RUN_NESTED_PROXY_E2E=1 \
      cargo test --manifest-path "$workspace_manifest" -p ripdpi-runtime --test network_e2e -- --nocapture
  }
fi

echo "==> relay fixture stack"
run_fixture_tests

echo "==> relay core and TUIC coverage"
run_relay_unit_tests

echo "==> relay interoperability end-to-end"
run_runtime_relay_e2e
