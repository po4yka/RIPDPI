#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$script_dir/stop-lab.sh"
"$script_dir/start-lab.sh" "$@"
