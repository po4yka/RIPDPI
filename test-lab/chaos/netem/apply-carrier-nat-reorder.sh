#!/usr/bin/env bash
set -euo pipefail

dev="${NETEM_DEV:-eth0}"
delay="${NETEM_REORDER_DELAY:-120ms}"
jitter="${NETEM_REORDER_JITTER:-40ms}"
reorder="${NETEM_REORDER_PERCENT:-25%}"
correlation="${NETEM_REORDER_CORRELATION:-50%}"
loss="${NETEM_REORDER_LOSS:-0.3%}"
duplicate="${NETEM_REORDER_DUPLICATE:-0.1%}"
nat_subnet="${NETEM_NAT_SUBNET:-}"
nat_out_dev="${NETEM_NAT_OUT_DEV:-$dev}"
sudo_cmd=(sudo)
if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  sudo_cmd=()
fi

"${sudo_cmd[@]}" tc qdisc replace dev "$dev" root netem delay "$delay" "$jitter" loss "$loss" duplicate "$duplicate" reorder "$reorder" "$correlation"

if [[ -n "$nat_subnet" ]]; then
  "${sudo_cmd[@]}" sysctl -w net.ipv4.ip_forward=1 >/dev/null
  if ! "${sudo_cmd[@]}" iptables -t nat -C POSTROUTING -s "$nat_subnet" -o "$nat_out_dev" -j MASQUERADE 2>/dev/null; then
    "${sudo_cmd[@]}" iptables -t nat -A POSTROUTING -s "$nat_subnet" -o "$nat_out_dev" -j MASQUERADE
  fi
fi
