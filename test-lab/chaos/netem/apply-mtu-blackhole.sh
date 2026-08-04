#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=test-lab/chaos/netem/lib.sh
source "$script_dir/lib.sh"
netem_init_session
dev="$netem_dev"
mtu="${1:-1280}"
"${netem_sudo[@]}" ip link set dev "$dev" mtu "$mtu"
netem_add_rule mtu-v4-output iptables OUTPUT -p icmp --icmp-type fragmentation-needed -j DROP
netem_add_rule mtu-v4-forward iptables FORWARD -p icmp --icmp-type fragmentation-needed -j DROP
netem_add_rule mtu-v6-output ip6tables OUTPUT -p ipv6-icmp --icmpv6-type packet-too-big -j DROP
netem_add_rule mtu-v6-forward ip6tables FORWARD -p ipv6-icmp --icmpv6-type packet-too-big -j DROP
