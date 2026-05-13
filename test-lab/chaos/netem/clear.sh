#!/usr/bin/env bash
set -euo pipefail

dev="${NETEM_DEV:-eth0}"
sudo tc qdisc del dev "$dev" root 2>/dev/null || true
sudo iptables -D INPUT -p udp --dport 9443 -j DROP 2>/dev/null || true
sudo iptables -D OUTPUT -p udp --sport 9443 -j DROP 2>/dev/null || true
