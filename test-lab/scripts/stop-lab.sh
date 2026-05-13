#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

(
  cd "$lab_root"
  docker compose down
)

rm -f "$lab_root/dns/Corefile.active"
echo "RIPDPI lab stopped."
