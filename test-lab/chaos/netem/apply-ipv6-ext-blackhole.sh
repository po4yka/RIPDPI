#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=test-lab/chaos/netem/lib.sh
source "$script_dir/lib.sh"
netem_init_session
netem_add_rule ipv6-hbh-output ip6tables OUTPUT -m hbh -j DROP
netem_add_rule ipv6-hbh-forward ip6tables FORWARD -m hbh -j DROP
netem_add_rule ipv6-dst-output ip6tables OUTPUT -m dst -j DROP
netem_add_rule ipv6-dst-forward ip6tables FORWARD -m dst -j DROP
