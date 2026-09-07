#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"

workspace_manifest="$repo_root/native/rust/Cargo.toml"

NEXTEST_PROFILE="${CI:+ci}"
NEXTEST_ARGS=(${NEXTEST_PROFILE:+--profile "$NEXTEST_PROFILE"})

# Fixture unit tests belong to the workspace lane.
echo "==> repo-owned proxy runtime E2E"
if bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest --version >/dev/null 2>&1; then
    bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked \
        --manifest-path "$workspace_manifest" -p ripdpi-proxy-runtime --test network_e2e \
        --no-capture "${NEXTEST_ARGS[@]}"
else
    cargo test --locked --manifest-path "$workspace_manifest" \
        -p ripdpi-proxy-runtime --test network_e2e -- --nocapture
fi

echo "==> standalone AmneziaWG independent peer interoperability"
python3 "$repo_root/scripts/tests/run-standalone-awg-interop.py"
