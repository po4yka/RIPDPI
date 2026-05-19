#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
log_file="$(mktemp "${TMPDIR:-/tmp}/ripdpi-host-udp-echo.XXXXXX.log")"
port="$(
  python3 - <<'PY'
import socket

with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
)"

RIPDPI_UDP_ECHO_HOST=127.0.0.1 RIPDPI_UDP_ECHO_PORT="$port" \
  python3 "$repo_root/test-lab/scripts/host-udp-echo.py" >"$log_file" 2>&1 &
pid="$!"

cleanup() {
  kill "$pid" >/dev/null 2>&1 || true
  wait "$pid" >/dev/null 2>&1 || true
  rm -f "$log_file"
}
trap cleanup EXIT

for _ in {1..30}; do
  if ! kill -0 "$pid" >/dev/null 2>&1; then
    cat "$log_file" >&2
    exit 1
  fi
  if python3 - "$port" <<'PY' >/dev/null 2>&1
import socket
import sys

port = int(sys.argv[1])
payload = b"ripdpi-host-udp-echo-test"
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.settimeout(0.25)
    sock.sendto(payload, ("127.0.0.1", port))
    data, _ = sock.recvfrom(65535)
if data != payload:
    raise SystemExit(f"echo mismatch: {data!r}")
PY
  then
    echo "Host UDP echo self-test passed."
    exit 0
  fi
  sleep 0.1
done

cat "$log_file" >&2
echo "Timed out waiting for host UDP echo on 127.0.0.1:$port" >&2
exit 1
