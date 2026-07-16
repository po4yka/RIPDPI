#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"

for command in cargo python3; do
    command -v "$command" >/dev/null || { echo "required command is unavailable: $command" >&2; exit 1; }
done

cargo test --locked --manifest-path "$workspace_manifest" -p ripdpi-tunnel-core \
    --test so_bindtodevice_e2e --no-run --message-format=json \
    | python3 -c '
import json
import os
import pathlib
import sys

executables = set()
for line in sys.stdin:
    try:
        event = json.loads(line)
    except json.JSONDecodeError:
        continue
    target = event.get("target", {})
    executable = event.get("executable")
    if (
        event.get("reason") == "compiler-artifact"
        and target.get("name") == "so_bindtodevice_e2e"
        and "test" in target.get("kind", [])
        and executable
    ):
        executables.add(executable)

if len(executables) != 1:
    raise SystemExit(
        f"expected exactly one compiled so_bindtodevice_e2e executable, found {len(executables)}"
    )

path = pathlib.Path(executables.pop()).resolve(strict=True)
if not path.is_file() or not os.access(path, os.X_OK):
    raise SystemExit(f"compiled SO_BINDTODEVICE test is not executable: {path}")
print(path)
'
