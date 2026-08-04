#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=test-lab/chaos/netem/lib.sh
source "$script_dir/lib.sh"
netem_init_session
dev="$netem_dev"
loss="${1:-10%}"
"${netem_sudo[@]}" tc qdisc replace dev "$dev" root netem loss "$loss"
