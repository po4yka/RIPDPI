#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"

log_file="${1:-$repo_root/build/local-network-fixture/fixture.log}"
manifest_file="${2:-$repo_root/build/local-network-fixture/fixture-manifest.json}"
pid_file="${3:-$repo_root/build/local-network-fixture/fixture.pid}"
identity_file="${pid_file}.identity"
control_port="${RIPDPI_FIXTURE_CONTROL_PORT:-46090}"
startup_timeout_seconds="${RIPDPI_FIXTURE_STARTUP_TIMEOUT_SECONDS:-300}"

if [[ ! "$startup_timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
    echo "RIPDPI_FIXTURE_STARTUP_TIMEOUT_SECONDS must be a positive integer" >&2
    exit 2
fi

mkdir -p "$(dirname "$log_file")" "$(dirname "$manifest_file")" "$(dirname "$pid_file")"
rm -f "$log_file" "$manifest_file" "$pid_file" "$identity_file"

nohup cargo run --locked \
    --manifest-path "$workspace_manifest" \
    -p local-network-fixture \
    --quiet \
    >"$log_file" 2>&1 </dev/null &
fixture_pid=$!
echo "$fixture_pid" >"$pid_file"
fixture_identity="$(ps -o lstart= -p "$fixture_pid" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
if [[ -z "$fixture_identity" ]]; then
    kill "$fixture_pid" 2>/dev/null || true
    wait "$fixture_pid" 2>/dev/null || true
    rm -f "$pid_file"
    echo "Could not capture local network fixture process identity" >&2
    exit 1
fi
printf '%s\n' "$fixture_identity" >"$identity_file"

stop_owned_fixture() {
    local current_identity
    if kill -0 "$fixture_pid" 2>/dev/null; then
        current_identity="$(ps -o lstart= -p "$fixture_pid" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
        if [[ "$current_identity" != "$fixture_identity" ]]; then
            echo "Refusing to stop PID $fixture_pid because its process identity changed" >&2
            return 1
        fi
        kill "$fixture_pid" 2>/dev/null || true
        wait "$fixture_pid" 2>/dev/null || true
    fi
    rm -f "$pid_file" "$identity_file"
}

for _ in $(seq 1 "$startup_timeout_seconds"); do
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
        stop_owned_fixture
        exit 1
    fi

    sleep 1
done

echo "Timed out waiting for local network fixture on control port ${control_port}" >&2
cat "$log_file" >&2 || true
stop_owned_fixture
exit 1
