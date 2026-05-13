#!/usr/bin/env bash
set -euo pipefail

dev="${NETEM_DEV:-eth0}"
delay="${1:-200ms}"
loss="${2:-0%}"

sudo tc qdisc replace dev "$dev" root netem delay "$delay" loss "$loss"
