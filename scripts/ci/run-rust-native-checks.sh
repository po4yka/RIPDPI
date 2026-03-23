#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"

workspace_manifest="$repo_root/native/rust/Cargo.toml"
export RIPDPI_GOLDEN_ARTIFACT_DIR="${RIPDPI_GOLDEN_ARTIFACT_DIR:-$repo_root/native/rust/target/golden-diffs}"

echo "==> rustfmt"
cargo fmt --manifest-path "$workspace_manifest" --all --check

echo "==> clippy"
cargo clippy --manifest-path "$workspace_manifest" --workspace --all-targets -- -D warnings

echo "==> cross-target check (Android ABIs)"
for target in aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android; do
  echo "  -> $target"
  cargo check --manifest-path "$workspace_manifest" --workspace --target "$target" --locked
done

echo "==> cargo-deny (workspace)"
cargo deny --manifest-path "$workspace_manifest" check

NEXTEST_PROFILE="${CI:+ci}"
NEXTEST_ARGS=(${NEXTEST_PROFILE:+--profile "$NEXTEST_PROFILE"})

echo "==> verify root workspace membership"
python3 - "$workspace_manifest" <<'PY'
import json
import subprocess
import sys

manifest = sys.argv[1]
result = subprocess.run(
    ["cargo", "metadata", "--manifest-path", manifest, "--format-version", "1", "--no-deps"],
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

echo "==> tests (workspace)"
cargo nextest run --manifest-path "$workspace_manifest" -p local-network-fixture "${NEXTEST_ARGS[@]}"
cargo nextest run --manifest-path "$workspace_manifest" -p ripdpi-tunnel-android "${NEXTEST_ARGS[@]}"
cargo nextest run --manifest-path "$workspace_manifest" -p ripdpi-android "${NEXTEST_ARGS[@]}"
cargo nextest run --manifest-path "$workspace_manifest" --workspace "${NEXTEST_ARGS[@]}"

echo "==> tests (ignored / smoke)"
cargo nextest run --manifest-path "$workspace_manifest" -p ripdpi-tunnel-android -E 'test(startup_latency_smoke)' --run-ignored ignored-only --no-capture "${NEXTEST_ARGS[@]}"
