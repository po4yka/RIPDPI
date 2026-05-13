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

if [[ -z "${DOCKER_CONFIG:-}" && -f "$HOME/.docker/config.json" ]] &&
  grep -q "docker-credential-desktop" "$HOME/.docker/config.json" &&
  ! command -v docker-credential-desktop >/dev/null 2>&1; then
  temp_docker_config="$(mktemp -d)"
  printf '{}\n' > "$temp_docker_config/config.json"
  export DOCKER_CONFIG="$temp_docker_config"
  trap 'rm -rf "$temp_docker_config"' EXIT
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

(
  cd "$lab_root"
  docker compose up -d --build
)

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
  DNS:   10.0.2.2
  HTTP:  http://10.0.2.2:8080/get
  HTTPS: https://10.0.2.2:8443/
  TCP:   10.0.2.2:9000
  UDP:   10.0.2.2:9001
  QUIC:  https://10.0.2.2:9443/h3/ok
  Relay: 10.0.2.2:10080

Device endpoints:
  DNS:   $macbook_lan_ip
  HTTP:  http://$macbook_lan_ip:8080/get
  HTTPS: https://$macbook_lan_ip:8443/
  TCP:   $macbook_lan_ip:9000
  UDP:   $macbook_lan_ip:9001
  QUIC:  https://$macbook_lan_ip:9443/h3/ok
  Relay: $macbook_lan_ip:10080
OUT
