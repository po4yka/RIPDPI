#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=test-lab/chaos/netem/lib.sh
source "$script_dir/lib.sh"
: "${NETEM_STATE_DIR:?NETEM_STATE_DIR is required for transactional cleanup}"
[[ -s "$NETEM_STATE_DIR/session.env" ]] || {
  echo "No captured netem session exists at NETEM_STATE_DIR" >&2
  exit 1
}
netem_init_session
dev="$netem_dev"

delete_rule() {
  local family="$1"
  shift
  "${netem_sudo[@]}" "$family" -D "$@" -m comment --comment "$netem_comment" 2>/dev/null || true
}

"${netem_sudo[@]}" tc qdisc del dev "$dev" root 2>/dev/null || true
delete_rule iptables OUTPUT -p icmp --icmp-type fragmentation-needed -j DROP
delete_rule iptables FORWARD -p icmp --icmp-type fragmentation-needed -j DROP
delete_rule ip6tables OUTPUT -p ipv6-icmp --icmpv6-type packet-too-big -j DROP
delete_rule ip6tables FORWARD -p ipv6-icmp --icmpv6-type packet-too-big -j DROP
delete_rule ip6tables OUTPUT -m hbh -j DROP
delete_rule ip6tables FORWARD -m hbh -j DROP
delete_rule ip6tables OUTPUT -m dst -j DROP
delete_rule ip6tables FORWARD -m dst -j DROP

if [[ -s "$netem_state_dir/quic.port" ]]; then
  port="$(cat "$netem_state_dir/quic.port")"
  delete_rule iptables INPUT -p udp --dport "$port" -j DROP
  delete_rule iptables OUTPUT -p udp --sport "$port" -j DROP
fi
if [[ -s "$netem_state_dir/nat.args" ]]; then
  nat_subnet="$(sed -n '1p' "$netem_state_dir/nat.args")"
  nat_out_dev="$(sed -n '2p' "$netem_state_dir/nat.args")"
  [[ -n "$nat_subnet" && -n "$nat_out_dev" && "$(wc -l <"$netem_state_dir/nat.args" | tr -d ' ')" == "2" ]] || {
    echo "Captured netem NAT arguments are malformed" >&2
    exit 1
  }
  "${netem_sudo[@]}" iptables -t nat -D POSTROUTING \
    -s "$nat_subnet" -o "$nat_out_dev" -j MASQUERADE \
    -m comment --comment "$netem_comment" 2>/dev/null || true
fi

"${netem_sudo[@]}" ip link set dev "$dev" mtu "$NETEM_CAPTURED_MTU"
"${netem_sudo[@]}" sysctl -w "net.ipv4.ip_forward=$NETEM_CAPTURED_IP_FORWARD" >/dev/null
tc qdisc show dev "$dev" >"$netem_state_dir/qdisc.after"
before_kind="$(awk '$1=="qdisc" && $0 ~ / root([[:space:]]|$)/ {print $2; exit}' "$netem_state_dir/qdisc.before")"
after_kind="$(awk '$1=="qdisc" && $0 ~ / root([[:space:]]|$)/ {print $2; exit}' "$netem_state_dir/qdisc.after")"
[[ "$before_kind" == "$after_kind" ]] || {
  echo "Netem cleanup did not restore the original root qdisc kind ($before_kind != $after_kind)" >&2
  exit 1
}
rm -rf "$netem_state_dir"
