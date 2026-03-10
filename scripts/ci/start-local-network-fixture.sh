#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"

log_file="${1:-$repo_root/build/local-network-fixture/fixture.log}"
manifest_file="${2:-$repo_root/build/local-network-fixture/fixture-manifest.json}"
pid_file="${3:-$repo_root/build/local-network-fixture/fixture.pid}"
control_port="${RIPDPI_FIXTURE_CONTROL_PORT:-46090}"

mkdir -p "$(dirname "$log_file")" "$(dirname "$manifest_file")" "$(dirname "$pid_file")"
rm -f "$log_file" "$manifest_file" "$pid_file"

nohup cargo run \
    --manifest-path "$workspace_manifest" \
    -p local-network-fixture \
    --quiet \
    >"$log_file" 2>&1 </dev/null &
fixture_pid=$!
echo "$fixture_pid" >"$pid_file"

for _ in $(seq 1 60); do
    if curl -fsS "http://127.0.0.1:${control_port}/health" >/dev/null 2>&1; then
        curl -fsS "http://127.0.0.1:${control_port}/manifest" >"$manifest_file"
        echo "fixture_pid=$fixture_pid"
        echo "fixture_log=$log_file"
        echo "fixture_manifest=$manifest_file"
        exit 0
    fi

    if ! kill -0 "$fixture_pid" 2>/dev/null; then
        echo "Local network fixture exited before becoming healthy" >&2
        cat "$log_file" >&2 || true
        exit 1
    fi

    sleep 1
done

echo "Timed out waiting for local network fixture on control port ${control_port}" >&2
cat "$log_file" >&2 || true
exit 1
