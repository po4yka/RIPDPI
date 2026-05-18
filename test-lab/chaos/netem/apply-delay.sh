#!/usr/bin/env bash
set -euo pipefail

dev="${NETEM_DEV:-eth0}"
delay="${1:-200ms}"
loss="${2:-0%}"
sudo_cmd=(sudo)
if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  sudo_cmd=()
fi

"${sudo_cmd[@]}" tc qdisc replace dev "$dev" root netem delay "$delay" loss "$loss"
