#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
lab_root="$repo_root/test-lab"
# shellcheck source=test-lab/scripts/python.sh
source "$lab_root/scripts/python.sh"
python_bin="$(ripdpi_resolve_python "netem forwarder")"
adb_bin="${ADB:-adb}"
docker_bin="${DOCKER:-docker}"
profile="${RIPDPI_LAB_PROFILE:-device}"
serial="${ANDROID_SERIAL:-}"
timeout_ms="${RIPDPI_PROBE_TIMEOUT_MS:-12000}"
image="${RIPDPI_NETEM_FORWARDER_IMAGE:-ripdpi/tspu-emulator:v1.1}"
docker_network="${RIPDPI_NETEM_DOCKER_NETWORK:-test-lab_default}"
delay="${RIPDPI_NETEM_DELAY:-200ms}"
jitter="${RIPDPI_NETEM_JITTER:-40ms}"
loss="${RIPDPI_NETEM_LOSS:-15%}"
out_dir="$lab_root/artifacts/netem-forwarder-tcp-$(date +%Y%m%d-%H%M%S)-$(git -C "$repo_root" rev-parse --short HEAD)"
skip_start=false
container_name=""

usage() {
  cat <<USAGE
Usage: $0 [options]

Runs a TCP-family diagnostics probe through a temporary Docker container with Linux tc netem.
DNS and UDP remain on host helpers because Docker Desktop UDP publication is not reliable for this lab.

Options:
  --profile device|physical|emulator  Probe profile, default RIPDPI_LAB_PROFILE or device
  --serial SERIAL                      ADB serial, default ANDROID_SERIAL; required
  --timeout-ms VALUE                   Debug probe timeout, default RIPDPI_PROBE_TIMEOUT_MS or 12000
  --delay VALUE                        Netem delay, default RIPDPI_NETEM_DELAY or 200ms
  --jitter VALUE                       Netem jitter, default RIPDPI_NETEM_JITTER or 40ms
  --loss VALUE                         Netem loss, default RIPDPI_NETEM_LOSS or 15%
  --out-dir PATH                       Artifact directory
  --skip-start                         Reuse the running local lab
  -h, --help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      profile="${2:?missing --profile value}"
      shift 2
      ;;
    --serial)
      serial="${2:?missing --serial value}"
      shift 2
      ;;
    --timeout-ms)
      timeout_ms="${2:?missing --timeout-ms value}"
      shift 2
      ;;
    --delay)
      delay="${2:?missing --delay value}"
      shift 2
      ;;
    --jitter)
      jitter="${2:?missing --jitter value}"
      shift 2
      ;;
    --loss)
      loss="${2:?missing --loss value}"
      shift 2
      ;;
    --out-dir)
      out_dir="${2:?missing --out-dir value}"
      shift 2
      ;;
    --skip-start)
      skip_start=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "$profile" in
  device|physical|emulator) ;;
  *)
    echo "Unsupported profile for netem forwarder: $profile" >&2
    exit 2
    ;;
esac

detect_lab_host() {
  MACBOOK_LAN_IP= "$lab_root/scripts/print-host-env.sh" | awk -F= -v profile="$profile" '
    profile == "emulator" && $1 == "EMULATOR_LAB_HOST" { print $2; found=1 }
    profile != "emulator" && $1 == "DEVICE_LAB_HOST" { print $2; found=1 }
    END { if (!found) exit 1 }
  '
}

free_tcp_port() {
  "$python_bin" - <<'PY'
import socket

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

cleanup() {
  if [[ -n "$container_name" ]]; then
    "$docker_bin" exec "$container_name" sh -lc 'tc qdisc del dev eth0 root 2>/dev/null || true' >/dev/null 2>&1 || true
    if [[ -d "$out_dir" ]]; then
      "$docker_bin" exec "$container_name" sh -lc 'tc qdisc show dev eth0' >"$out_dir/tc-after-clear.txt" 2>/dev/null || true
    fi
    "$docker_bin" rm -f "$container_name" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_for_container() {
  for _ in {1..50}; do
    if "$docker_bin" exec "$container_name" sh -lc 'test -f /tmp/netem-forwarder.ready' >/dev/null 2>&1; then
      return 0
    fi
    if [[ "$("$docker_bin" inspect -f '{{.State.Running}}' "$container_name" 2>/dev/null || true)" != "true" ]]; then
      "$docker_bin" logs "$container_name" >&2 || true
      return 1
    fi
    sleep 0.2
  done
  "$docker_bin" logs "$container_name" >&2 || true
  echo "Timed out waiting for netem forwarder container." >&2
  return 1
}

write_forwarder() {
  local path="$1"
  cat >"$path" <<'PY'
#!/usr/bin/env python3
import select
import socket
import sys
import threading
from dataclasses import dataclass


@dataclass(frozen=True)
class Forward:
    listen_port: int
    target_host: str
    target_port: int


def pump(left: socket.socket, right: socket.socket) -> None:
    sockets = [left, right]
    try:
        while True:
            readable, _, _ = select.select(sockets, [], [], 60)
            if not readable:
                return
            for source in readable:
                data = source.recv(64 * 1024)
                if not data:
                    return
                target = right if source is left else left
                target.sendall(data)
    finally:
        for sock in sockets:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            sock.close()


def serve(forward: Forward) -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("0.0.0.0", forward.listen_port))
        listener.listen(64)
        print(
            f"forward listen=0.0.0.0:{forward.listen_port} "
            f"target={forward.target_host}:{forward.target_port}",
            flush=True,
        )
        while True:
            client, source = listener.accept()
            try:
                upstream = socket.create_connection((forward.target_host, forward.target_port), timeout=10)
            except OSError as error:
                print(f"connect failed source={source} target={forward.target_host}:{forward.target_port} error={error}", flush=True)
                client.close()
                continue
            threading.Thread(target=pump, args=(client, upstream), daemon=True).start()


def parse_args(argv: list[str]) -> list[Forward]:
    if len(argv) % 3 != 0 or not argv:
        raise SystemExit("usage: forwarder.py LISTEN_PORT TARGET_HOST TARGET_PORT ...")
    forwards = []
    for index in range(0, len(argv), 3):
        forwards.append(Forward(int(argv[index]), argv[index + 1], int(argv[index + 2])))
    return forwards


def main() -> int:
    forwards = parse_args(sys.argv[1:])
    for forward in forwards:
        threading.Thread(target=serve, args=(forward,), daemon=True).start()
    with open("/tmp/netem-forwarder.ready", "w", encoding="utf-8") as ready:
        ready.write("ready\n")
    threading.Event().wait()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
PY
}

assert_probe_ok() {
  local probe_json="$1"
  jq -e '
    (.errors | length) == 0 and
    .dns.ok == true and
    .http.ok == true and
    .https.ok == true and
    .tcp.ok == true and
    .udp.ok == true and
    .relayReady == true
  ' "$probe_json" >/dev/null
}

run_probe() {
  local name="$1"
  local probe_dir="$out_dir/$name"
  mkdir -p "$probe_dir"
  ANDROID_SERIAL="$serial" "$lab_root/scripts/adb-run-probe.sh" \
    --profile "$profile" \
    --mode diagnostics \
    --lab-host "$lab_host" \
    --timeout-ms "$timeout_ms" \
    --http-url "http://$lab_host:$http_port/get" \
    --https-url "https://$lab_host:$https_port/" \
    --tcp-host "$lab_host" \
    --tcp-port "$tcp_port" \
    --relay-endpoint "$lab_host:$relay_port" \
    --out-dir "$probe_dir" >"$probe_dir/probe.log"
  assert_probe_ok "$probe_dir/probe-${profile}-diagnostics.json"
}

mkdir -p "$out_dir"
if [[ "$skip_start" != "true" ]]; then
  "$lab_root/scripts/restart-lab.sh" --profile "$profile" >"$out_dir/restart-lab.log"
fi

lab_host="$(detect_lab_host)"
if [[ -z "$serial" ]]; then
  echo "ANDROID_SERIAL or --serial is required; the netem runner never auto-selects a target." >&2
  exit 2
fi

http_port="$(free_tcp_port)"
https_port="$(free_tcp_port)"
tcp_port="$(free_tcp_port)"
relay_port="$(free_tcp_port)"
container_name="ripdpi-netem-forwarder-$(date +%s)-$$"
forwarder_py="$out_dir/forwarder.py"
write_forwarder "$forwarder_py"

"$adb_bin" devices -l >"$out_dir/adb-devices.txt"
cat >"$out_dir/ports.env" <<PORTS
LAB_HOST=$lab_host
HTTP_PORT=$http_port
HTTPS_PORT=$https_port
TCP_PORT=$tcp_port
RELAY_PORT=$relay_port
DOCKER_NETWORK=$docker_network
CONTAINER=$container_name
PORTS

"$docker_bin" run -d --rm \
  --name "$container_name" \
  --cap-add NET_ADMIN \
  --cap-add NET_RAW \
  --network "$docker_network" \
  -p "$http_port:$http_port/tcp" \
  -p "$https_port:$https_port/tcp" \
  -p "$tcp_port:$tcp_port/tcp" \
  -p "$relay_port:$relay_port/tcp" \
  -v "$forwarder_py:/forwarder.py:ro" \
  --entrypoint python3 \
  "$image" \
  /forwarder.py \
  "$http_port" httpbin 80 \
  "$https_port" caddy 443 \
  "$tcp_port" tcp_echo 9000 \
  "$relay_port" mock_relay 10080 >"$out_dir/container-id.txt"

wait_for_container
"$docker_bin" logs "$container_name" >"$out_dir/container-start.log" 2>&1 || true
"$docker_bin" exec "$container_name" sh -lc 'tc qdisc show dev eth0' >"$out_dir/tc-baseline.txt"

run_probe baseline_tcp_forwarder

if [[ -n "$jitter" ]]; then
  "$docker_bin" exec "$container_name" sh -lc "tc qdisc replace dev eth0 root netem delay '$delay' '$jitter'"
else
  "$docker_bin" exec "$container_name" sh -lc "tc qdisc replace dev eth0 root netem delay '$delay'"
fi
"$docker_bin" exec "$container_name" sh -lc 'tc qdisc show dev eth0' >"$out_dir/tc-delay.txt"
run_probe delay_tcp_forwarder

"$docker_bin" exec "$container_name" sh -lc "tc qdisc replace dev eth0 root netem loss '$loss'"
"$docker_bin" exec "$container_name" sh -lc 'tc qdisc show dev eth0' >"$out_dir/tc-loss.txt"
run_probe loss_tcp_forwarder

cleanup
trap - EXIT

{
  printf 'run\tverdict\terrors\tdns\thttp\thttp_ms\thttps\thttps_ms\ttcp\ttcp_ms\tudp\trelayReady\n'
  for run_name in baseline_tcp_forwarder delay_tcp_forwarder loss_tcp_forwarder; do
    probe_json="$out_dir/$run_name/probe-${profile}-diagnostics.json"
    jq -r --arg run "$run_name" '
      [
        $run,
        .verdict,
        (.errors | length),
        .dns.ok,
        .http.ok,
        .http.latencyMs,
        .https.ok,
        .https.latencyMs,
        .tcp.ok,
        .tcp.latencyMs,
        .udp.ok,
        .relayReady
      ] | @tsv
    ' "$probe_json"
  done
} >"$out_dir/summary.tsv"

cat "$out_dir/summary.tsv"
echo "Netem TCP forwarder diagnostics passed. Artifacts: $out_dir"
