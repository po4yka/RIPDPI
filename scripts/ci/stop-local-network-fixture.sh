#!/usr/bin/env bash
set -euo pipefail

pid_file="${1:-}"
identity_file="${pid_file}.identity"
if [[ -z "$pid_file" || ! -f "$pid_file" ]]; then
    exit 0
fi

fixture_pid="$(cat "$pid_file")"
if [[ ! "$fixture_pid" =~ ^[1-9][0-9]*$ || ! -s "$identity_file" ]]; then
    echo "Refusing to stop a fixture without a complete process identity" >&2
    exit 1
fi

if kill -0 "$fixture_pid" 2>/dev/null; then
    expected_identity="$(cat "$identity_file")"
    current_identity="$(ps -o lstart= -p "$fixture_pid" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    if [[ -z "$current_identity" || "$current_identity" != "$expected_identity" ]]; then
        echo "Refusing to stop PID $fixture_pid because its process identity changed" >&2
        exit 1
    fi
    kill "$fixture_pid" 2>/dev/null || true
    wait "$fixture_pid" 2>/dev/null || true
fi

rm -f "$pid_file" "$identity_file"
