#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=test-lab/scripts/python.sh
source "$lab_root/scripts/python.sh"
profile="${RIPDPI_LAB_PROFILE:-emulator}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      profile="${2:?missing --profile value}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

detect_lan_ip() {
  for iface in "${RIPDPI_LAB_IFACE:-}" en0 en1 bridge100; do
    [[ -n "$iface" ]] || continue
    ipconfig getifaddr "$iface" 2>/dev/null && return 0
  done
  hostname -I 2>/dev/null | awk '{print $1}' && return 0
  return 1
}

macbook_lan_ip="${MACBOOK_LAN_IP:-$(detect_lan_ip)}"
host_udp_echo_port="${RIPDPI_UDP_ECHO_PORT:-9001}"
host_udp_echo_pid_file="$lab_root/artifacts/host-udp-echo.pid"
host_udp_echo_log="$lab_root/artifacts/host-udp-echo.log"
host_udp_echo_setting="${RIPDPI_HOST_UDP_ECHO:-auto}"
host_dns_pid_file="$lab_root/artifacts/host-dns.pid"
host_dns_log="$lab_root/artifacts/host-dns.log"
host_dns_setting="${RIPDPI_HOST_DNS:-auto}"
use_host_udp_echo=false
use_host_dns=false
python_bin=""

resolve_host_python() {
  local purpose="$1"
  if [[ -z "$python_bin" ]]; then
    python_bin="$(ripdpi_resolve_python "$purpose")"
  fi
}

case "$host_udp_echo_setting" in
  1|true|TRUE|yes|YES)
    use_host_udp_echo=true
    ;;
  0|false|FALSE|no|NO)
    use_host_udp_echo=false
    ;;
  auto)
    if [[ "$(uname -s)" == "Darwin" ]]; then
      use_host_udp_echo=true
    fi
    ;;
  *)
    echo "Unsupported RIPDPI_HOST_UDP_ECHO value: $host_udp_echo_setting" >&2
    exit 2
    ;;
esac

case "$host_dns_setting" in
  1|true|TRUE|yes|YES)
    use_host_dns=true
    ;;
  0|false|FALSE|no|NO)
    use_host_dns=false
    ;;
  auto)
    if [[ "$(uname -s)" == "Darwin" ]]; then
      use_host_dns=true
    fi
    ;;
  *)
    echo "Unsupported RIPDPI_HOST_DNS value: $host_dns_setting" >&2
    exit 2
    ;;
esac

stop_host_udp_echo() {
  if [[ -f "$host_udp_echo_pid_file" ]]; then
    local pid
    pid="$(cat "$host_udp_echo_pid_file" 2>/dev/null || true)"
    if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then
      kill "$pid" >/dev/null 2>&1 || true
      for _ in {1..30}; do
        kill -0 "$pid" >/dev/null 2>&1 || break
        sleep 0.1
      done
      kill -9 "$pid" >/dev/null 2>&1 || true
    fi
    rm -f "$host_udp_echo_pid_file"
  fi
}

stop_host_dns() {
  if [[ -f "$host_dns_pid_file" ]]; then
    local pid
    pid="$(cat "$host_dns_pid_file" 2>/dev/null || true)"
    if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then
      kill "$pid" >/dev/null 2>&1 || true
      for _ in {1..30}; do
        kill -0 "$pid" >/dev/null 2>&1 || break
        sleep 0.1
      done
      kill -9 "$pid" >/dev/null 2>&1 || true
    fi
    rm -f "$host_dns_pid_file"
  fi
}

host_dns_record_ip() {
  case "$profile" in
    emulator)
      printf '10.0.2.2'
      ;;
    device|physical)
      printf '%s' "$macbook_lan_ip"
      ;;
  esac
}

check_host_udp_echo() {
  UDP_ECHO_PORT="$host_udp_echo_port" "$python_bin" - <<'PY'
import os
import socket

port = int(os.environ["UDP_ECHO_PORT"])
payload = b"ripdpi-host-udp-echo-ready"
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.settimeout(0.5)
    sock.sendto(payload, ("127.0.0.1", port))
    data, _ = sock.recvfrom(65535)
if data != payload:
    raise SystemExit(f"echo mismatch: {data!r}")
PY
}

check_host_dns() {
  DNS_PORT="$dns_port" DNS_RECORD_IP="$(host_dns_record_ip)" "$python_bin" - <<'PY'
import os
import socket
import struct

port = int(os.environ["DNS_PORT"])
record_ip = os.environ["DNS_RECORD_IP"]
qname = b"".join(bytes([len(part)]) + part.encode("ascii") for part in "ok.test".split(".")) + b"\x00"
query = struct.pack("!HHHHHH", 0xD411, 0x0100, 1, 0, 0, 0) + qname + struct.pack("!HH", 1, 1)
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
    sock.settimeout(0.5)
    sock.sendto(query, ("127.0.0.1", port))
    response, _ = sock.recvfrom(4096)
if socket.inet_aton(record_ip) not in response:
    raise SystemExit(f"missing expected A record {record_ip} in {response!r}")
PY
}

start_host_udp_echo() {
  resolve_host_python "host UDP echo service"
  stop_host_udp_echo
  mkdir -p "$lab_root/artifacts"
  RIPDPI_UDP_ECHO_HOST=0.0.0.0 RIPDPI_UDP_ECHO_PORT="$host_udp_echo_port" \
    "$python_bin" "$lab_root/scripts/host-udp-echo.py" \
      --daemonize \
      --pid-file "$host_udp_echo_pid_file" \
      --log-file "$host_udp_echo_log"
  for _ in {1..30}; do
    [[ -f "$host_udp_echo_pid_file" ]] && break
    sleep 0.1
  done
  local pid
  pid="$(cat "$host_udp_echo_pid_file" 2>/dev/null || true)"
  if [[ -z "$pid" ]]; then
    if [[ -f "$host_udp_echo_log" ]]; then
      cat "$host_udp_echo_log" >&2
    fi
    echo "Host UDP echo did not write a PID file." >&2
    return 1
  fi
  for _ in {1..30}; do
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      cat "$host_udp_echo_log" >&2
      return 1
    fi
    if check_host_udp_echo >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.2
  done
  cat "$host_udp_echo_log" >&2
  echo "Timed out waiting for host UDP echo on port $host_udp_echo_port." >&2
  return 1
}

start_host_dns() {
  resolve_host_python "host DNS service"
  stop_host_dns
  mkdir -p "$lab_root/artifacts"
  RIPDPI_DNS_HOST=0.0.0.0 RIPDPI_DNS_PORT="$dns_port" RIPDPI_LAB_RECORD_IP="$(host_dns_record_ip)" \
    "$python_bin" "$lab_root/scripts/host-dns.py" \
      --daemonize \
      --pid-file "$host_dns_pid_file" \
      --log-file "$host_dns_log"
  for _ in {1..30}; do
    [[ -f "$host_dns_pid_file" ]] && break
    sleep 0.1
  done
  local pid
  pid="$(cat "$host_dns_pid_file" 2>/dev/null || true)"
  if [[ -z "$pid" ]]; then
    if [[ -f "$host_dns_log" ]]; then
      cat "$host_dns_log" >&2
    fi
    echo "Host DNS did not write a PID file." >&2
    return 1
  fi
  for _ in {1..30}; do
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      cat "$host_dns_log" >&2
      return 1
    fi
    if check_host_dns >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.2
  done
  cat "$host_dns_log" >&2
  echo "Timed out waiting for host DNS on port $dns_port." >&2
  return 1
}

ensure_lab_certificate() {
  local cert_dir="$lab_root/tls/certs"
  local cert_path="$cert_dir/lab.crt"
  local key_path="$cert_dir/lab.key"
  if [[ -f "$cert_path" && -f "$key_path" ]]; then
    return 0
  fi
  if ! command -v openssl >/dev/null 2>&1; then
    echo "openssl is required to generate the debug lab TLS certificate." >&2
    return 1
  fi

  mkdir -p "$cert_dir"
  local cert_config
  cert_config="$(mktemp)"
  cat > "$cert_config" <<EOF
[req]
distinguished_name = req_distinguished_name
x509_extensions = v3_req
prompt = no

[req_distinguished_name]
CN = ripdpi-local.test

[v3_req]
subjectAltName = @alt_names

[alt_names]
DNS.1 = localhost
DNS.2 = ok.test
DNS.3 = tls.test
DNS.4 = quic.test
IP.1 = 127.0.0.1
IP.2 = 10.0.2.2
IP.3 = $macbook_lan_ip
EOF
  openssl req \
    -x509 \
    -nodes \
    -newkey rsa:2048 \
    -days 3650 \
    -keyout "$key_path" \
    -out "$cert_path" \
    -config "$cert_config" >/dev/null 2>&1
  rm -f "$cert_config"
}

port_in_use() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 && return 0
    lsof -nP -iUDP:"$port" >/dev/null 2>&1 && return 0
  fi
  return 1
}

dns_port="${RIPDPI_DNS_PORT:-1053}"
if [[ "$dns_port" == "53" ]] && port_in_use 53; then
  echo "Port 53 appears to be in use; set RIPDPI_DNS_PORT=1053 or free port 53." >&2
fi
export RIPDPI_DNS_PORT="$dns_port"

using_temp_docker_config=false
if [[ -z "${DOCKER_CONFIG:-}" && -f "$HOME/.docker/config.json" ]] &&
  grep -Eq '"credsStore"[[:space:]]*:|"credHelpers"[[:space:]]*:|docker-credential-' "$HOME/.docker/config.json"; then
  docker_context="${DOCKER_CONTEXT:-$(docker context show 2>/dev/null || true)}"
  if [[ -z "${DOCKER_HOST:-}" && -n "$docker_context" ]]; then
    docker_host="$(docker context inspect "$docker_context" --format '{{json .Endpoints.docker.Host}}' 2>/dev/null | tr -d '"' || true)"
    if [[ -n "$docker_host" && "$docker_host" != "<no value>" ]]; then
      export DOCKER_HOST="$docker_host"
    fi
  fi
  temp_docker_config="$(mktemp -d)"
  printf '{}\n' > "$temp_docker_config/config.json"
  export DOCKER_CONFIG="$temp_docker_config"
  using_temp_docker_config=true
  trap 'rm -rf "$temp_docker_config"' EXIT
fi

compose_cmd=(docker compose)
if ! "${compose_cmd[@]}" version >/dev/null 2>&1; then
  if [[ -x /Applications/Docker.app/Contents/Resources/cli-plugins/docker-compose ]]; then
    compose_cmd=(/Applications/Docker.app/Contents/Resources/cli-plugins/docker-compose)
  else
    compose_cmd=(docker-compose)
  fi
fi

if [[ "$using_temp_docker_config" == "true" ]]; then
  for image in \
    coredns/coredns:latest \
    kennethreitz/httpbin:latest \
    wiremock/wiremock:latest \
    caddy:latest \
    alpine/socat:latest \
    shopify/toxiproxy:latest \
    golang:1.22-alpine \
    golang:1.24-alpine \
    alpine:3.20; do
    docker image inspect "$image" >/dev/null 2>&1 || docker pull "$image" >/dev/null
  done
  local_image_specs=(
    "test-lab-quic_server:latest:$lab_root/quic/quic-go-server"
    "test-lab-mock_relay:latest:$lab_root/relay/mock-relay"
  )
  if [[ "$use_host_udp_echo" != "true" ]]; then
    local_image_specs+=("test-lab-udp_echo:latest:$lab_root/udp-echo")
  fi
  for image_spec in "${local_image_specs[@]}"; do
    image_name="${image_spec%%:*}:latest"
    image_context="${image_spec#*:latest:}"
    if ! docker image inspect "$image_name" >/dev/null 2>&1; then
      docker build --pull=false -t "$image_name" "$image_context" >/dev/null
    fi
  done
fi

case "$profile" in
  emulator)
    cp "$lab_root/dns/Corefile.emulator" "$lab_root/dns/Corefile.active"
    ;;
  device|physical)
    sed "s/\${MACBOOK_LAN_IP}/$macbook_lan_ip/g" \
      "$lab_root/dns/Corefile.device.template" > "$lab_root/dns/Corefile.active"
    ;;
  *)
    echo "Unsupported profile: $profile" >&2
    exit 2
    ;;
esac

ensure_lab_certificate

(
  cd "$lab_root"
  compose_up_args=(up -d)
  if [[ "$using_temp_docker_config" == "true" ]]; then
    compose_up_args+=(--no-build --pull never)
  else
    compose_up_args+=(--build)
  fi
  if [[ "$use_host_udp_echo" == "true" ]]; then
    stop_host_udp_echo
    compose_up_args+=(--scale udp_echo=0)
  else
    stop_host_udp_echo
  fi
  if [[ "$use_host_dns" == "true" ]]; then
    stop_host_dns
    compose_up_args+=(--scale coredns=0)
  else
    stop_host_dns
  fi
  "${compose_cmd[@]}" "${compose_up_args[@]}"
)

if [[ "$use_host_dns" == "true" ]]; then
  start_host_dns
fi
if [[ "$use_host_udp_echo" == "true" ]]; then
  start_host_udp_echo
fi

mkdir -p "$lab_root/artifacts"
cat > "$lab_root/artifacts/lab-env.sh" <<ENV
export MACBOOK_LAN_IP="$macbook_lan_ip"
export RIPDPI_DNS_PORT="$dns_port"
export RIPDPI_LAB_PROFILE="$profile"
export RIPDPI_HOST_UDP_ECHO="$use_host_udp_echo"
export RIPDPI_HOST_DNS="$use_host_dns"
ENV

wait_for_http() {
  local url="$1"
  for _ in {1..60}; do
    if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for $url" >&2
  return 1
}

wait_for_http "http://127.0.0.1:8080/get"
wait_for_http "http://127.0.0.1:8082/ripdpi/ok"

cat <<OUT
RIPDPI lab started.

Host LAN IP: $macbook_lan_ip
Profile: $profile

Emulator endpoints:
  DNS:   10.0.2.2:$dns_port
  HTTP:  http://10.0.2.2:8080/get
  HTTPS: https://10.0.2.2:8443/
  TCP:   10.0.2.2:9000
  UDP:   10.0.2.2:9001
  QUIC:  https://10.0.2.2:9443/h3/ok
  Relay: 10.0.2.2:10080

Device endpoints:
  DNS:   $macbook_lan_ip:$dns_port
  HTTP:  http://$macbook_lan_ip:8080/get
  HTTPS: https://$macbook_lan_ip:8443/
  TCP:   $macbook_lan_ip:9000
  UDP:   $macbook_lan_ip:9001
  QUIC:  https://$macbook_lan_ip:9443/h3/ok
  Relay: $macbook_lan_ip:10080

DNS backend: $(if [[ "$use_host_dns" == "true" ]]; then echo "host process ($host_dns_log)"; else echo "Docker Compose coredns"; fi)
UDP echo backend: $(if [[ "$use_host_udp_echo" == "true" ]]; then echo "host process ($host_udp_echo_log)"; else echo "Docker Compose udp_echo"; fi)
OUT
