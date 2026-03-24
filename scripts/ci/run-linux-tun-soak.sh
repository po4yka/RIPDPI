#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"
artifact_dir="${1:-$repo_root/build/linux-tun-soak-artifacts}"

mkdir -p "$artifact_dir"

export RIPDPI_RUN_SOAK="${RIPDPI_RUN_SOAK:-1}"
export RIPDPI_RUN_TUN_E2E="${RIPDPI_RUN_TUN_E2E:-1}"
export RIPDPI_SOAK_PROFILE="${RIPDPI_SOAK_PROFILE:-smoke}"
export RIPDPI_SOAK_ARTIFACT_DIR="$artifact_dir"

target_exists="$(
python3 - "$workspace_manifest" <<'PY'
import json
import subprocess
import sys

manifest = sys.argv[1]
metadata = json.loads(
    subprocess.run(
        ["cargo", "metadata", "--manifest-path", manifest, "--format-version", "1", "--no-deps"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
)

for package in metadata["packages"]:
    if package["name"] != "ripdpi-tunnel-android":
        continue
    for target in package["targets"]:
        if target["name"] == "linux_tun_soak" and "test" in target["kind"]:
            print("yes")
            raise SystemExit(0)

print("no")
PY
)"

if [ "$target_exists" != "yes" ]; then
    echo "==> linux_tun_soak target is not present in ripdpi-tunnel-android; skipping stale privileged lane"
    exit 0
fi

echo "==> ripdpi-tunnel-android linux tun soak"
cargo test --manifest-path "$workspace_manifest" -p ripdpi-tunnel-android --test linux_tun_soak -- --ignored --nocapture --test-threads=1

# ── ripdpi-tunnel-core privileged TUN E2E ─────────────────────────────────────

tunnel_core_e2e_exists="$(
python3 - "$workspace_manifest" <<'PY'
import json
import subprocess
import sys

manifest = sys.argv[1]
metadata = json.loads(
    subprocess.run(
        ["cargo", "metadata", "--manifest-path", manifest, "--format-version", "1", "--no-deps"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
)

for package in metadata["packages"]:
    if package["name"] != "ripdpi-tunnel-core":
        continue
    for target in package["targets"]:
        if target["name"] == "linux_tun_e2e" and "test" in target["kind"]:
            print("yes")
            raise SystemExit(0)

print("no")
PY
)"

if [ "$tunnel_core_e2e_exists" = "yes" ]; then
    echo "==> ripdpi-tunnel-core linux tun e2e"
    cargo test --manifest-path "$workspace_manifest" -p ripdpi-tunnel-core --test linux_tun_e2e -- --ignored --nocapture --test-threads=1
else
    echo "==> linux_tun_e2e target is not present in ripdpi-tunnel-core; skipping"
fi
