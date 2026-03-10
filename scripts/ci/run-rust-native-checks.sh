#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"

workspace_manifest="$repo_root/native/rust/Cargo.toml"
byedpi_manifest="$repo_root/native/rust/third_party/byedpi/Cargo.toml"

echo "==> rustfmt"
cargo fmt --manifest-path "$workspace_manifest" --all --check

echo "==> clippy"
cargo clippy --manifest-path "$workspace_manifest" --workspace --all-targets -- -D warnings

NEXTEST_PROFILE="${CI:+ci}"
NEXTEST_ARGS=(${NEXTEST_PROFILE:+--profile "$NEXTEST_PROFILE"})

echo "==> tests (workspace)"
cargo nextest run --manifest-path "$workspace_manifest" -p local-network-fixture "${NEXTEST_ARGS[@]}"
cargo nextest run --manifest-path "$workspace_manifest" -p hs5t-android "${NEXTEST_ARGS[@]}"
cargo nextest run --manifest-path "$workspace_manifest" -p ripdpi-android "${NEXTEST_ARGS[@]}"
cargo nextest run --manifest-path "$workspace_manifest" --workspace "${NEXTEST_ARGS[@]}"

# The vendored byedpi host integration suite contains several slow and flaky
# end-to-end cases that do not exercise the Android JNI migration path
# directly. Keep focused smoke coverage here instead of running the full host
# integration binary.
echo "==> tests (byedpi)"
cargo nextest run --manifest-path "$byedpi_manifest" -p ciadpi-bin --bin ciadpi "${NEXTEST_ARGS[@]}"
cargo nextest run --manifest-path "$byedpi_manifest" -p ciadpi-bin --test cli "${NEXTEST_ARGS[@]}"
cargo nextest run --manifest-path "$byedpi_manifest" -p ciadpi-bin --test runtime_integration -E 'test(socks5_echo_round_trip)' "${NEXTEST_ARGS[@]}"
cargo nextest run --manifest-path "$byedpi_manifest" -p ciadpi-bin --test runtime_integration -E 'test(no_domain_rejects_domain_requests)' "${NEXTEST_ARGS[@]}"
cargo nextest run --manifest-path "$byedpi_manifest" -p ciadpi-bin --test runtime_integration -E 'test(external_socks_chain_round_trip)' "${NEXTEST_ARGS[@]}"

echo "==> tests (ignored / smoke)"
cargo nextest run --manifest-path "$byedpi_manifest" -p ciadpi-packets -E 'test(benchmark_smoke)' --run-ignored ignored-only --no-capture "${NEXTEST_ARGS[@]}"
cargo nextest run --manifest-path "$workspace_manifest" -p hs5t-android -E 'test(startup_latency_smoke)' --run-ignored ignored-only --no-capture "${NEXTEST_ARGS[@]}"
