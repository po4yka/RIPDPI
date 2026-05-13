#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

compose_cmd=(docker compose)
if ! "${compose_cmd[@]}" version >/dev/null 2>&1; then
  if [[ -x /Applications/Docker.app/Contents/Resources/cli-plugins/docker-compose ]]; then
    compose_cmd=(/Applications/Docker.app/Contents/Resources/cli-plugins/docker-compose)
  else
    compose_cmd=(docker-compose)
  fi
fi

(
  cd "$lab_root"
  "${compose_cmd[@]}" down
)

rm -f "$lab_root/dns/Corefile.active"
echo "RIPDPI lab stopped."
