#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"

byedpi_manifest="$repo_root/native/rust/third_party/byedpi/Cargo.toml"
hev_manifest="$repo_root/native/rust/third_party/hev-socks5-tunnel/Cargo.toml"

cargo fmt --manifest-path "$byedpi_manifest" --all --check
cargo fmt --manifest-path "$hev_manifest" --all --check

# The vendored byedpi host integration suite contains several slow and flaky
# end-to-end cases that do not exercise the Android JNI migration path
# directly. Keep focused smoke coverage here instead of running the full host
# integration binary.
cargo test --manifest-path "$byedpi_manifest" --workspace --exclude ciadpi-bin
cargo test --manifest-path "$byedpi_manifest" -p ciadpi-bin --bin ciadpi
cargo test --manifest-path "$byedpi_manifest" -p ciadpi-bin --test cli
cargo test --manifest-path "$byedpi_manifest" -p ciadpi-bin --test runtime_integration socks5_echo_round_trip
cargo test --manifest-path "$byedpi_manifest" -p ciadpi-bin --test runtime_integration no_domain_rejects_domain_requests
cargo test --manifest-path "$byedpi_manifest" -p ciadpi-bin --test runtime_integration external_socks_chain_round_trip
cargo test --manifest-path "$hev_manifest" --workspace
