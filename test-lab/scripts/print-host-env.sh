#!/usr/bin/env bash
set -euo pipefail

detect_lan_ip() {
  for iface in "${RIPDPI_LAB_IFACE:-}" en0 en1 bridge100; do
    [[ -n "$iface" ]] || continue
    ipconfig getifaddr "$iface" 2>/dev/null && return 0
  done
  hostname -I 2>/dev/null | awk '{print $1}' && return 0
  return 1
}

lan_ip="$(detect_lan_ip)"

cat <<ENV
MACBOOK_LAN_IP=$lan_ip
EMULATOR_LAB_HOST=10.0.2.2
DEVICE_LAB_HOST=$lan_ip
HTTP_EMULATOR=http://10.0.2.2:8080/get
HTTP_DEVICE=http://$lan_ip:8080/get
DNS_EMULATOR=10.0.2.2
DNS_DEVICE=$lan_ip
TCP_PORT=9000
UDP_PORT=9001
QUIC_URL_EMULATOR=https://10.0.2.2:9443/h3/ok
QUIC_URL_DEVICE=https://$lan_ip:9443/h3/ok
ENV
