#!/usr/bin/env bash
set -euo pipefail

sudo_cmd=(sudo)
if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  sudo_cmd=()
fi

"${sudo_cmd[@]}" ip6tables -I OUTPUT -m hbh -j DROP
"${sudo_cmd[@]}" ip6tables -I FORWARD -m hbh -j DROP
"${sudo_cmd[@]}" ip6tables -I OUTPUT -m dst -j DROP
"${sudo_cmd[@]}" ip6tables -I FORWARD -m dst -j DROP
