#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
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
    alpine:3.20; do
    docker image inspect "$image" >/dev/null 2>&1 || docker pull "$image" >/dev/null
  done
  local_image_specs=(
    "test-lab-quic_server:latest:$lab_root/quic/quic-go-server"
    "test-lab-udp_echo:latest:$lab_root/udp-echo"
    "test-lab-mock_relay:latest:$lab_root/relay/mock-relay"
  )
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
  if [[ "$using_temp_docker_config" == "true" ]]; then
    "${compose_cmd[@]}" up -d --no-build --pull never
  else
    "${compose_cmd[@]}" up -d --build
  fi
)

mkdir -p "$lab_root/artifacts"
cat > "$lab_root/artifacts/lab-env.sh" <<ENV
export MACBOOK_LAN_IP="$macbook_lan_ip"
export RIPDPI_DNS_PORT="$dns_port"
export RIPDPI_LAB_PROFILE="$profile"
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
OUT
