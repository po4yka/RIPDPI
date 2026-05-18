#!/usr/bin/env bash
set -euo pipefail

dev="${NETEM_DEV:-eth0}"
port="${1:-9443}"
sudo_cmd=(sudo)
if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  sudo_cmd=()
fi

"${sudo_cmd[@]}" iptables -I INPUT -p udp --dport "$port" -j DROP
"${sudo_cmd[@]}" iptables -I OUTPUT -p udp --sport "$port" -j DROP
