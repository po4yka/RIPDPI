#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=test-lab/scripts/python.sh
source "$repo_root/test-lab/scripts/python.sh"
python_bin="$(ripdpi_resolve_python "host DNS tests")"
log_file="$(mktemp "${TMPDIR:-/tmp}/ripdpi-host-dns.XXXXXX.log")"
port="$(
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
)"

RIPDPI_DNS_HOST=127.0.0.1 RIPDPI_DNS_PORT="$port" RIPDPI_LAB_RECORD_IP=127.0.0.42 \
  "$python_bin" "$repo_root/test-lab/scripts/host-dns.py" >"$log_file" 2>&1 &
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
  if "$python_bin" - "$port" <<'PY' >/dev/null 2>&1
import socket
import struct
import sys
import time

port = int(sys.argv[1])


def build_query(transaction_id: int) -> bytes:
    qname = b"".join(bytes([len(part)]) + part.encode("ascii") for part in "ok.test".split(".")) + b"\x00"
    return struct.pack("!HHHHHH", transaction_id, 0x0100, 1, 0, 0, 0) + qname + struct.pack("!HH", 1, 1)


def assert_answer(packet: bytes) -> None:
    if socket.inet_aton("127.0.0.42") not in packet:
        raise SystemExit(f"missing expected A record in {packet!r}")


udp_query = build_query(0x1234)
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.settimeout(0.25)
    sock.sendto(udp_query, ("127.0.0.1", port))
    udp_response, _ = sock.recvfrom(4096)
assert_answer(udp_response)

tcp_query = build_query(0x2345)
with socket.create_connection(("127.0.0.1", port), timeout=0.25) as sock:
    sock.sendall(struct.pack("!H", len(tcp_query)) + tcp_query)
    length_prefix = sock.recv(2)
    response_length = struct.unpack("!H", length_prefix)[0]
    tcp_response = sock.recv(response_length)
assert_answer(tcp_response)

split_tcp_query = build_query(0x3456)
length_prefix = struct.pack("!H", len(split_tcp_query))
with socket.create_connection(("127.0.0.1", port), timeout=0.25) as sock:
    sock.sendall(length_prefix[:1])
    time.sleep(0.05)
    sock.sendall(length_prefix[1:] + split_tcp_query)
    response_prefix = sock.recv(2)
    response_length = struct.unpack("!H", response_prefix)[0]
    split_tcp_response = sock.recv(response_length)
assert_answer(split_tcp_response)
PY
  then
    echo "Host DNS self-test passed."
    exit 0
  fi
  sleep 0.1
done

cat "$log_file" >&2
echo "Timed out waiting for host DNS on 127.0.0.1:$port" >&2
exit 1
