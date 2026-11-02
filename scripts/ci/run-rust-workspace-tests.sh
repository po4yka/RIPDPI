#!/usr/bin/env bash
set -euo pipefail

# Rust workspace tests and architecture contract checks.
# Split from run-rust-native-checks.sh for parallel CI execution.
#
# Usage:
#   run-rust-workspace-tests.sh                  checks, then build and run every workspace test
#   run-rust-workspace-tests.sh checks           architecture checks, AWG backend, socket-filter tests
#   run-rust-workspace-tests.sh archive FILE     build the workspace test binaries into a nextest archive
#   run-rust-workspace-tests.sh shard FILE K/N   run slice K of N of the workspace tests from FILE

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"
export RIPDPI_GOLDEN_ARTIFACT_DIR="${RIPDPI_GOLDEN_ARTIFACT_DIR:-$repo_root/native/rust/target/golden-diffs}"

NEXTEST_PROFILE="${CI:+ci}"
NEXTEST_ARGS=(${NEXTEST_PROFILE:+--profile "$NEXTEST_PROFILE"})

# Exclude integration test binaries that have their own CI steps
# (run-rust-network-e2e.sh, run-rust-turmoil-tests.sh) and platform tests
# needing CAP_NET_ADMIN.
WORKSPACE_FILTER='not binary(network_e2e) and not binary(tun_e2e) and not ((package(=ripdpi-tunnel-core) or package(=ripdpi-dns-resolver)) and test(turmoil_)) and not test(/^platform::linux::tests::bpf_/) and not (package(=ripdpi-privileged-ops) and test(/::bpf_/)) and not (package(=ripdpi-proxy-runtime) and test(=runtime::routing::connect::post_connect::tests::strip_timestamps_does_not_replace_the_drop_sack_filter)) and not test(/^platform::linux::tests::tcp_window_clamp/) and not test(/^runtime::tests::window_clamp/)'
SMOKE_FILTER='test(startup_latency_smoke)'

run_checks() {
  echo "==> verify root workspace membership"
  python3 - "$workspace_manifest" <<'PY'
import json
import subprocess
import sys

manifest = sys.argv[1]
result = subprocess.run(
    ["cargo", "metadata", "--locked", "--manifest-path", manifest, "--format-version", "1", "--no-deps"],
    check=True,
    capture_output=True,
    text=True,
)
workspace_members = json.loads(result.stdout)["workspace_members"]
third_party_members = [member for member in workspace_members if "/third_party/" in member]
if third_party_members:
    print("error: native/rust root workspace must stay first-party only", file=sys.stderr)
    for member in third_party_members:
        print(member, file=sys.stderr)
    sys.exit(1)
PY

  echo "==> native hotspot budgets"
  python3 "$repo_root/scripts/ci/check_native_hotspot_budgets.py"

  echo "==> native architecture checker tests"
  python3 "$repo_root/scripts/ci/test_native_architecture_contracts.py"

  echo "==> native architecture contracts"
  python3 "$repo_root/scripts/ci/check_native_architecture_contracts.py"

  # This patched dependency has its own workspace, so the workspace-wide run skips it.
  echo "==> tests (AWG backend)"
  bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo test --locked \
    --manifest-path "$repo_root/native/rust/vendor/boringtun/Cargo.toml" --lib

  if [[ "$(uname -s)" == "Linux" ]]; then
    echo "==> socket-filter tests (runner UID with CAP_NET_ADMIN)"
    python3 "$repo_root/scripts/ci/run-linux-bpf-tests.py"
    python3 "$repo_root/scripts/ci/run-linux-bpf-tests.py" --suite proxy-runtime
  fi
}

case "${1:-all}" in
  all)
    run_checks
    echo "==> tests (workspace)"
    bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked --manifest-path "$workspace_manifest" --workspace \
      -E "$WORKSPACE_FILTER" "${NEXTEST_ARGS[@]}"
    # Keep workspace feature unification so the smoke test reuses the same binaries.
    echo "==> tests (ignored / smoke)"
    bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked --manifest-path "$workspace_manifest" --workspace \
      -E "$SMOKE_FILTER" --run-ignored ignored-only --no-capture "${NEXTEST_ARGS[@]}"
    ;;
  checks)
    run_checks
    ;;
  archive)
    echo "==> archive workspace test binaries"
    bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest archive --locked --manifest-path "$workspace_manifest" --workspace \
      --archive-file "${2:?archive file required}"
    ;;
  shard)
    archive_file="${2:?archive file required}"
    partition="${3:?partition K/N required}"
    echo "==> tests (workspace, slice $partition)"
    cargo nextest run --archive-file "$archive_file" --workspace-remap "$repo_root/native/rust" \
      --partition "slice:$partition" -E "$WORKSPACE_FILTER" "${NEXTEST_ARGS[@]}"
    if [[ "${partition%%/*}" == 1 ]]; then
      echo "==> tests (ignored / smoke)"
      cargo nextest run --archive-file "$archive_file" --workspace-remap "$repo_root/native/rust" \
        -E "$SMOKE_FILTER" --run-ignored ignored-only --no-capture "${NEXTEST_ARGS[@]}"
    fi
    ;;
  *)
    echo "usage: $0 [all | checks | archive FILE | shard FILE K/N]" >&2
    exit 2
    ;;
esac
