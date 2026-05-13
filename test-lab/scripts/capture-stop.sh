#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pid_file="$lab_root/capture/tcpdump.pid"

if [[ ! -f "$pid_file" ]]; then
  echo "No capture pid file at $pid_file" >&2
  exit 1
fi

pid="$(cat "$pid_file")"
sudo kill -INT "$pid" 2>/dev/null || true
rm -f "$pid_file"
echo "tcpdump stopped."
