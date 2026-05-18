#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
host_udp_echo_pid_file="$lab_root/artifacts/host-udp-echo.pid"
host_dns_pid_file="$lab_root/artifacts/host-dns.pid"

if [[ -f "$host_udp_echo_pid_file" ]]; then
  pid="$(cat "$host_udp_echo_pid_file" 2>/dev/null || true)"
  if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then
    kill "$pid" >/dev/null 2>&1 || true
    for _ in {1..30}; do
      kill -0 "$pid" >/dev/null 2>&1 || break
      sleep 0.1
    done
    kill -9 "$pid" >/dev/null 2>&1 || true
  fi
  rm -f "$host_udp_echo_pid_file"
fi
if [[ -f "$host_dns_pid_file" ]]; then
  pid="$(cat "$host_dns_pid_file" 2>/dev/null || true)"
  if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then
    kill "$pid" >/dev/null 2>&1 || true
    for _ in {1..30}; do
      kill -0 "$pid" >/dev/null 2>&1 || break
      sleep 0.1
    done
    kill -9 "$pid" >/dev/null 2>&1 || true
  fi
  rm -f "$host_dns_pid_file"
fi

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
