#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"

NEXTEST_PROFILE="${CI:+ci}"
NEXTEST_ARGS=(${NEXTEST_PROFILE:+--profile "$NEXTEST_PROFILE"})

# Unit and fixture tests run in the workspace lane. This lane exercises
# the runtime with nested proxy routing enabled; upstream peers run in CI.
echo "==> relay interoperability end-to-end"
if bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest --version >/dev/null 2>&1; then
  bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked \
    --manifest-path "$workspace_manifest" -p ripdpi-proxy-runtime --test network_e2e \
    --run-ignored ignored-only --no-capture "${NEXTEST_ARGS[@]}"
else
  cargo test --locked --manifest-path "$workspace_manifest" \
    -p ripdpi-proxy-runtime --test network_e2e -- --ignored --nocapture
fi
