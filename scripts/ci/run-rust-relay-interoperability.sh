#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"

NEXTEST_PROFILE="${CI:+ci}"
NEXTEST_ARGS=(${NEXTEST_PROFILE:+--profile "$NEXTEST_PROFILE"})

if bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest --version >/dev/null 2>&1; then
  run_fixture_tests() {
    bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked --manifest-path "$workspace_manifest" -p local-network-fixture "${NEXTEST_ARGS[@]}"
  }

  run_transport_interop_tests() {
    bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked \
      --manifest-path "$workspace_manifest" \
      -p ripdpi-xhttp \
      -p ripdpi-vless \
      -p ripdpi-hysteria2 \
      -p ripdpi-tuic \
      -p ripdpi-shadowtls \
      -p ripdpi-anytls \
      -p ripdpi-ssh \
      -p ripdpi-mieru \
      -p ripdpi-masque \
      -p ripdpi-cloudflare-origin \
      -p ripdpi-naiveproxy \
      "${NEXTEST_ARGS[@]}"
  }

  run_relay_core_tests() {
    bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked --manifest-path "$workspace_manifest" -p ripdpi-relay-core "${NEXTEST_ARGS[@]}"
  }

  run_runtime_relay_e2e() {
    RIPDPI_RUN_NESTED_PROXY_E2E=1 \
      bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked --manifest-path "$workspace_manifest" -p ripdpi-proxy-runtime --test network_e2e --no-capture "${NEXTEST_ARGS[@]}"
  }
else
  run_fixture_tests() {
    cargo test --locked --manifest-path "$workspace_manifest" -p local-network-fixture -- --nocapture
  }

  run_transport_interop_tests() {
    cargo test --locked \
      --manifest-path "$workspace_manifest" \
      -p ripdpi-xhttp \
      -p ripdpi-vless \
      -p ripdpi-hysteria2 \
      -p ripdpi-tuic \
      -p ripdpi-shadowtls \
      -p ripdpi-anytls \
      -p ripdpi-ssh \
      -p ripdpi-mieru \
      -p ripdpi-masque \
      -p ripdpi-cloudflare-origin \
      -p ripdpi-naiveproxy \
      -- --nocapture
  }

  run_relay_core_tests() {
    cargo test --locked --manifest-path "$workspace_manifest" -p ripdpi-relay-core -- --nocapture
  }

  run_runtime_relay_e2e() {
    RIPDPI_RUN_NESTED_PROXY_E2E=1 \
      cargo test --locked --manifest-path "$workspace_manifest" -p ripdpi-proxy-runtime --test network_e2e -- --nocapture
  }
fi

echo "==> relay fixture stack"
run_fixture_tests

echo "==> transport interoperability coverage"
run_transport_interop_tests

echo "==> relay core coverage"
run_relay_core_tests

echo "==> relay interoperability end-to-end"
run_runtime_relay_e2e
