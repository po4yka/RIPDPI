#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"
tun_device="${RIPDPI_TUN_DEVICE:-/dev/net/tun}"

if [ "$(uname -s)" != "Linux" ]; then
    echo "linux_tun_e2e requires a Linux host" >&2
    exit 1
fi
if [ "${RIPDPI_RUN_TUN_E2E:-}" != "1" ]; then
    echo "RIPDPI_RUN_TUN_E2E=1 is required" >&2
    exit 1
fi
if [ ! -c "$tun_device" ]; then
    echo "Linux TUN device is unavailable: $tun_device" >&2
    exit 1
fi

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
        if target["name"] == "linux_tun_e2e" and "test" in target["kind"]:
            print("yes")
            raise SystemExit(0)

print("no")
PY
)"

if [ "$target_exists" != "yes" ]; then
    echo "linux_tun_e2e target is not present in ripdpi-tunnel-core" >&2
    exit 1
fi

echo "==> ripdpi-tunnel-core linux tun e2e"
cargo test --locked --manifest-path "$workspace_manifest" -p ripdpi-tunnel-core --test linux_tun_e2e e2e_ -- --ignored --nocapture
