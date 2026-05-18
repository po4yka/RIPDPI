#!/usr/bin/env bash
set -euo pipefail

dev="${NETEM_DEV:-eth0}"
sudo_cmd=(sudo)
if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  sudo_cmd=()
fi

"${sudo_cmd[@]}" tc qdisc del dev "$dev" root 2>/dev/null || true
"${sudo_cmd[@]}" iptables -D INPUT -p udp --dport 9443 -j DROP 2>/dev/null || true
"${sudo_cmd[@]}" iptables -D OUTPUT -p udp --sport 9443 -j DROP 2>/dev/null || true
