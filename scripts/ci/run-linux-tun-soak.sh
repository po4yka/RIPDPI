#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"
artifact_dir="${1:-$repo_root/build/linux-tun-soak-artifacts}"
tun_device="${RIPDPI_TUN_DEVICE:-/dev/net/tun}"

mkdir -p "$artifact_dir"

export RIPDPI_RUN_SOAK="${RIPDPI_RUN_SOAK:-1}"
export RIPDPI_RUN_TUN_E2E="${RIPDPI_RUN_TUN_E2E:-1}"
export RIPDPI_SOAK_PROFILE="${RIPDPI_SOAK_PROFILE:-smoke}"
export RIPDPI_SOAK_ARTIFACT_DIR="$artifact_dir"

if [ "$(uname -s)" != "Linux" ]; then
    echo "linux_tun_soak requires a Linux host" >&2
    exit 1
fi
if [ ! -c "$tun_device" ]; then
    echo "Linux TUN device is unavailable: $tun_device" >&2
    exit 1
fi

case "$RIPDPI_SOAK_PROFILE" in
    smoke)
        profile_iterations=50
        ;;
    full)
        profile_iterations=500
        ;;
    *)
        echo "unsupported RIPDPI_SOAK_PROFILE: $RIPDPI_SOAK_PROFILE (expected smoke or full)" >&2
        exit 1
        ;;
esac

if [ -n "${RIPDPI_SOAK_ITERS:-}" ]; then
    if ! [[ "$RIPDPI_SOAK_ITERS" =~ ^[1-9][0-9]*$ ]]; then
        echo "RIPDPI_SOAK_ITERS must be a positive integer" >&2
        exit 1
    fi
else
    export RIPDPI_SOAK_ITERS="$profile_iterations"
fi

printf 'profile=%s\niterations=%s\n' \
    "$RIPDPI_SOAK_PROFILE" \
    "$RIPDPI_SOAK_ITERS" \
    > "$artifact_dir/effective-profile.txt"

target_exists="$(
python3 - "$workspace_manifest" <<'PY'
import json
import subprocess
import sys

manifest = sys.argv[1]
metadata = json.loads(
    subprocess.run(
        ["cargo", "metadata", "--locked", "--manifest-path", manifest, "--format-version", "1", "--no-deps"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
)

for package in metadata["packages"]:
    if package["name"] != "ripdpi-tunnel-core":
        continue
    for target in package["targets"]:
        if target["name"] == "linux_tun_soak" and "test" in target["kind"]:
            print("yes")
            raise SystemExit(0)

print("no")
PY
)"

if [ "$target_exists" != "yes" ]; then
    echo "linux_tun_soak target is not present in ripdpi-tunnel-core" >&2
    exit 1
fi

echo "==> ripdpi-tunnel-core linux tun soak ($RIPDPI_SOAK_PROFILE, $RIPDPI_SOAK_ITERS iterations)"
cargo test --locked --manifest-path "$workspace_manifest" -p ripdpi-tunnel-core --test linux_tun_soak soak_ -- --ignored --nocapture --test-threads=1
