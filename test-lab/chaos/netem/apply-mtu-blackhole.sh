#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=test-lab/chaos/netem/lib.sh
source "$script_dir/lib.sh"
netem_init_session
dev="$netem_dev"
mtu="${1:-1280}"
"${netem_sudo[@]}" ip link set dev "$dev" mtu "$mtu"
netem_add_rule iptables OUTPUT -p icmp --icmp-type fragmentation-needed -j DROP
netem_add_rule iptables FORWARD -p icmp --icmp-type fragmentation-needed -j DROP
netem_add_rule ip6tables OUTPUT -p ipv6-icmp --icmpv6-type packet-too-big -j DROP
netem_add_rule ip6tables FORWARD -p ipv6-icmp --icmpv6-type packet-too-big -j DROP
