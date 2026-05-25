#!/usr/bin/env bash
set -euo pipefail

dev="${NETEM_DEV:-eth0}"
mtu="${1:-1280}"
safe_dev="$(printf '%s' "$dev" | tr '/: ' '___')"
state_file="${TMPDIR:-/tmp}/ripdpi-netem-${safe_dev}.mtu"
sudo_cmd=(sudo)
if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  sudo_cmd=()
fi

if [[ ! -f "$state_file" ]]; then
  ip link show dev "$dev" | awk '{
    for (i = 1; i <= NF; i += 1) {
      if ($i == "mtu") {
        print $(i + 1)
        exit
      }
    }
  }' >"$state_file"
fi

"${sudo_cmd[@]}" ip link set dev "$dev" mtu "$mtu"
"${sudo_cmd[@]}" iptables -I OUTPUT -p icmp --icmp-type fragmentation-needed -j DROP
"${sudo_cmd[@]}" iptables -I FORWARD -p icmp --icmp-type fragmentation-needed -j DROP
"${sudo_cmd[@]}" ip6tables -I OUTPUT -p ipv6-icmp --icmpv6-type packet-too-big -j DROP
"${sudo_cmd[@]}" ip6tables -I FORWARD -p ipv6-icmp --icmpv6-type packet-too-big -j DROP
