#!/usr/bin/env bash
set -euo pipefail

pid_file="${1:-}"
if [[ -z "$pid_file" || ! -f "$pid_file" ]]; then
    exit 0
fi

fixture_pid="$(cat "$pid_file")"
if [[ -z "$fixture_pid" ]]; then
    exit 0
fi

if kill -0 "$fixture_pid" 2>/dev/null; then
    kill "$fixture_pid" 2>/dev/null || true
    wait "$fixture_pid" 2>/dev/null || true
fi

rm -f "$pid_file"
