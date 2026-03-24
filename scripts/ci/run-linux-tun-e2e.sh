#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"

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
        if target["name"] == "linux_tun_e2e" and "test" in target["kind"]:
            print("yes")
            raise SystemExit(0)

print("no")
PY
)"

if [ "$target_exists" != "yes" ]; then
    echo "==> linux_tun_e2e target is not present in ripdpi-tunnel-android; skipping stale privileged lane"
    exit 0
fi

echo "==> ripdpi-tunnel-android linux tun e2e"
cargo test --manifest-path "$workspace_manifest" -p ripdpi-tunnel-android --test linux_tun_e2e -- --ignored --nocapture
