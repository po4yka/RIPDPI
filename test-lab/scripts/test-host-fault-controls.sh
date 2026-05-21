#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=test-lab/scripts/python.sh
source "$repo_root/test-lab/scripts/python.sh"
python_bin="$(ripdpi_resolve_python "host fault-control tests")"
tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-host-fault-controls.XXXXXX")"
pids=()

cleanup() {
  for pid in "${pids[@]}"; do
    kill "$pid" >/dev/null 2>&1 || true
    wait "$pid" >/dev/null 2>&1 || true
  done
  rm -rf "$tmpdir"
}
trap cleanup EXIT

free_udp_port() {
  "$python_bin" - <<'PY'
import socket

with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

free_dual_port() {
  "$python_bin" - <<'PY'
import socket

for _ in range(100):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as tcp_sock:
        tcp_sock.bind(("127.0.0.1", 0))
        port = tcp_sock.getsockname()[1]
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp_sock:
            try:
                udp_sock.bind(("127.0.0.1", port))
            except OSError:
                continue
            print(port)
            raise SystemExit(0)
raise SystemExit("no free TCP+UDP port found")
PY
}

wait_for_log() {
  local log_file="$1"
  local pattern="$2"
  for _ in {1..30}; do
    if grep -Fq "$pattern" "$log_file" 2>/dev/null; then
      return 0
    fi
    sleep 0.1
  done
  cat "$log_file" >&2 || true
  echo "Timed out waiting for '$pattern' in $log_file" >&2
  return 1
}

udp_port="$(free_udp_port)"
udp_log="$tmpdir/udp.log"
RIPDPI_UDP_ECHO_HOST=127.0.0.1 \
  RIPDPI_UDP_ECHO_PORT="$udp_port" \
  RIPDPI_UDP_ECHO_DELAY_MS=10 \
  RIPDPI_UDP_ECHO_DROP_EVERY=2 \
  "$python_bin" "$repo_root/test-lab/scripts/host-udp-echo.py" >"$udp_log" 2>&1 &
pids+=("$!")
wait_for_log "$udp_log" "host udp echo listening"

"$python_bin" - "$udp_port" <<'PY'
import socket
import sys

port = int(sys.argv[1])


def exchange(payload: bytes, expect_echo: bool) -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.settimeout(0.5)
        sock.sendto(payload, ("127.0.0.1", port))
        try:
            data, _ = sock.recvfrom(65535)
        except socket.timeout:
            if expect_echo:
                raise SystemExit(f"timed out waiting for echo {payload!r}")
            return
        if not expect_echo:
            raise SystemExit(f"expected dropped datagram, got {data!r}")
        if data != payload:
            raise SystemExit(f"echo mismatch: {data!r}")


exchange(b"first-echo", True)
exchange(b"second-dropped", False)
exchange(b"third-echo", True)
PY

dns_port="$(free_dual_port)"
dns_log="$tmpdir/dns.log"
RIPDPI_DNS_HOST=127.0.0.1 \
  RIPDPI_DNS_PORT="$dns_port" \
  RIPDPI_LAB_RECORD_IP=127.0.0.42 \
  RIPDPI_DNS_UDP_DROP_EVERY=1 \
  "$python_bin" "$repo_root/test-lab/scripts/host-dns.py" >"$dns_log" 2>&1 &
pids+=("$!")
wait_for_log "$dns_log" "host dns listening"

"$python_bin" - "$dns_port" <<'PY'
import socket
import struct
import sys

port = int(sys.argv[1])


def build_query(transaction_id: int) -> bytes:
    qname = b"".join(bytes([len(part)]) + part.encode("ascii") for part in "ok.test".split(".")) + b"\x00"
    return struct.pack("!HHHHHH", transaction_id, 0x0100, 1, 0, 0, 0) + qname + struct.pack("!HH", 1, 1)


udp_query = build_query(0x1234)
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.settimeout(0.3)
    sock.sendto(udp_query, ("127.0.0.1", port))
    try:
        sock.recvfrom(4096)
    except socket.timeout:
        pass
    else:
        raise SystemExit("expected DNS UDP query to be dropped")

tcp_query = build_query(0x2345)
with socket.create_connection(("127.0.0.1", port), timeout=0.5) as sock:
    sock.sendall(struct.pack("!H", len(tcp_query)) + tcp_query)
    length_prefix = sock.recv(2)
    response_length = struct.unpack("!H", length_prefix)[0]
    tcp_response = sock.recv(response_length)
if socket.inet_aton("127.0.0.42") not in tcp_response:
    raise SystemExit(f"missing expected A record in {tcp_response!r}")
PY

echo "Host DNS/UDP fault-control self-test passed."
