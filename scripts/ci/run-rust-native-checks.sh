#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"

workspace_manifest="$repo_root/native/rust/Cargo.toml"
byedpi_manifest="$repo_root/native/rust/third_party/byedpi/Cargo.toml"

cargo fmt --manifest-path "$workspace_manifest" --all --check
cargo clippy --manifest-path "$workspace_manifest" --workspace --all-targets -- -D warnings
cargo test --manifest-path "$workspace_manifest" --workspace

# The vendored byedpi host integration suite contains several slow and flaky
# end-to-end cases that do not exercise the Android JNI migration path
# directly. Keep focused smoke coverage here instead of running the full host
# integration binary.
cargo test --manifest-path "$byedpi_manifest" -p ciadpi-bin --bin ciadpi
cargo test --manifest-path "$byedpi_manifest" -p ciadpi-bin --test cli
cargo test --manifest-path "$byedpi_manifest" -p ciadpi-bin --test runtime_integration socks5_echo_round_trip
cargo test --manifest-path "$byedpi_manifest" -p ciadpi-bin --test runtime_integration no_domain_rejects_domain_requests
cargo test --manifest-path "$byedpi_manifest" -p ciadpi-bin --test runtime_integration external_socks_chain_round_trip
cargo test --manifest-path "$byedpi_manifest" -p ciadpi-packets benchmark_smoke -- --ignored --nocapture
cargo test --manifest-path "$workspace_manifest" -p hs5t-android startup_latency_smoke -- --ignored --nocapture
