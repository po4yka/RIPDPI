#!/usr/bin/env bash
set -euo pipefail

dev="${NETEM_DEV:-eth0}"
safe_dev="$(printf '%s' "$dev" | tr '/: ' '___')"
state_file="${TMPDIR:-/tmp}/ripdpi-netem-${safe_dev}.mtu"
sudo_cmd=(sudo)
if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  sudo_cmd=()
fi

"${sudo_cmd[@]}" tc qdisc del dev "$dev" root 2>/dev/null || true
"${sudo_cmd[@]}" iptables -D OUTPUT -p icmp --icmp-type fragmentation-needed -j DROP 2>/dev/null || true
"${sudo_cmd[@]}" iptables -D FORWARD -p icmp --icmp-type fragmentation-needed -j DROP 2>/dev/null || true
"${sudo_cmd[@]}" ip6tables -D OUTPUT -p ipv6-icmp --icmpv6-type packet-too-big -j DROP 2>/dev/null || true
"${sudo_cmd[@]}" ip6tables -D FORWARD -p ipv6-icmp --icmpv6-type packet-too-big -j DROP 2>/dev/null || true
"${sudo_cmd[@]}" ip6tables -D OUTPUT -m hbh -j DROP 2>/dev/null || true
"${sudo_cmd[@]}" ip6tables -D FORWARD -m hbh -j DROP 2>/dev/null || true
"${sudo_cmd[@]}" ip6tables -D OUTPUT -m dst -j DROP 2>/dev/null || true
"${sudo_cmd[@]}" ip6tables -D FORWARD -m dst -j DROP 2>/dev/null || true
"${sudo_cmd[@]}" iptables -D INPUT -p udp --dport 9443 -j DROP 2>/dev/null || true
"${sudo_cmd[@]}" iptables -D OUTPUT -p udp --sport 9443 -j DROP 2>/dev/null || true
if [[ -n "${NETEM_NAT_SUBNET:-}" ]]; then
  "${sudo_cmd[@]}" iptables -t nat -D POSTROUTING -s "$NETEM_NAT_SUBNET" -o "${NETEM_NAT_OUT_DEV:-$dev}" -j MASQUERADE 2>/dev/null || true
fi
if [[ -s "$state_file" ]]; then
  original_mtu="$(cat "$state_file")"
  if [[ "$original_mtu" =~ ^[0-9]+$ ]]; then
    "${sudo_cmd[@]}" ip link set dev "$dev" mtu "$original_mtu" 2>/dev/null || true
  fi
  rm -f "$state_file"
fi
