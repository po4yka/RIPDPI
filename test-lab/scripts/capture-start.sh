#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
iface="${RIPDPI_CAPTURE_IFACE:-en0}"
phone_ip="${PHONE_IP:-${1:-}}"
filter="${RIPDPI_CAPTURE_FILTER:-}"
output="${RIPDPI_CAPTURE_OUTPUT:-$lab_root/capture/phone-all.pcap}"
pid_file="$lab_root/capture/tcpdump.pid"

if [[ -z "$filter" ]]; then
  [[ -n "$phone_ip" ]] || { echo "Set PHONE_IP or pass it as the first argument." >&2; exit 2; }
  filter="host $phone_ip"
fi

mkdir -p "$lab_root/capture"
sudo tcpdump -i "$iface" -nn -s 0 -w "$output" $filter &
echo $! > "$pid_file"
echo "tcpdump started: pid=$(cat "$pid_file") output=$output filter=$filter"
