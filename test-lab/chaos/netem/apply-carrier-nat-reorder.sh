#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=test-lab/chaos/netem/lib.sh
source "$script_dir/lib.sh"
netem_init_session
dev="$netem_dev"
delay="${NETEM_REORDER_DELAY:-120ms}"
jitter="${NETEM_REORDER_JITTER:-40ms}"
reorder="${NETEM_REORDER_PERCENT:-25%}"
correlation="${NETEM_REORDER_CORRELATION:-50%}"
loss="${NETEM_REORDER_LOSS:-0.3%}"
duplicate="${NETEM_REORDER_DUPLICATE:-0.1%}"
nat_subnet="${NETEM_NAT_SUBNET:-}"
nat_out_dev="${NETEM_NAT_OUT_DEV:-$dev}"
"${netem_sudo[@]}" tc qdisc replace dev "$dev" root netem delay "$delay" "$jitter" loss "$loss" duplicate "$duplicate" reorder "$reorder" "$correlation"

if [[ -n "$nat_subnet" ]]; then
  "${netem_sudo[@]}" sysctl -w net.ipv4.ip_forward=1 >/dev/null
  printf '%s\n%s\n' "$nat_subnet" "$nat_out_dev" >"$netem_state_dir/nat.args"
  if ! "${netem_sudo[@]}" iptables -t nat -C POSTROUTING -s "$nat_subnet" -o "$nat_out_dev" -j MASQUERADE -m comment --comment "$netem_comment" 2>/dev/null; then
    "${netem_sudo[@]}" iptables -t nat -A POSTROUTING -s "$nat_subnet" -o "$nat_out_dev" -j MASQUERADE -m comment --comment "$netem_comment"
  fi
fi
