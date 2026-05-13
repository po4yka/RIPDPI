#!/usr/bin/env bash
set -euo pipefail

dev="${NETEM_DEV:-eth0}"
port="${1:-9443}"

sudo iptables -I INPUT -p udp --dport "$port" -j DROP
sudo iptables -I OUTPUT -p udp --sport "$port" -j DROP
