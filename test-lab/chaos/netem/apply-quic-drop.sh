#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=test-lab/chaos/netem/lib.sh
source "$script_dir/lib.sh"
netem_init_session
port="${1:-9443}"
printf '%s\n' "$port" >"$netem_state_dir/quic.port"
netem_add_rule quic-input iptables INPUT -p udp --dport "$port" -j DROP
netem_add_rule quic-output iptables OUTPUT -p udp --sport "$port" -j DROP
