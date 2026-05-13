#!/usr/bin/env bash
set -euo pipefail

dev="${NETEM_DEV:-eth0}"
loss="${1:-10%}"

sudo tc qdisc replace dev "$dev" root netem loss "$loss"
