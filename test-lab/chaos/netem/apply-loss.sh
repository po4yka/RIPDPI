#!/usr/bin/env bash
set -euo pipefail

dev="${NETEM_DEV:-eth0}"
loss="${1:-10%}"
sudo_cmd=(sudo)
if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  sudo_cmd=()
fi

"${sudo_cmd[@]}" tc qdisc replace dev "$dev" root netem loss "$loss"
