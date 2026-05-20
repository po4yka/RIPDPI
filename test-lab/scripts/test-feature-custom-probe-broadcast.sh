#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

command="$(
  "$repo_root/test-lab/scripts/adb-run-probe.sh" \
    --profile custom \
    --mode diagnostics \
    --host lab.example.test \
    --dns-server dns.example.test \
    --dns-port 1053 \
    --dns-hostname ok.test \
    --http-url http://lab.example.test:8080/get \
    --https-url https://lab.example.test:8443/ \
    --tcp-host tcp.example.test \
    --tcp-port 9000 \
    --udp-host udp.example.test \
    --udp-port 9001 \
    --relay-endpoint relay.example.test:10080 \
    --require-vpn-active false \
    --require-proxy-ready false \
    --print-broadcast
)"

printf '%s\n' "$command"
grep -Fq -- "--es profile custom" <<<"$command"
grep -Fq -- "--es mode diagnostics" <<<"$command"
grep -Fq -- "--es lab_host lab.example.test" <<<"$command"
grep -Fq -- "--ei dns_port 1053" <<<"$command"
grep -Fq -- "--es dns_server dns.example.test" <<<"$command"
grep -Fq -- "--es dns_hostname ok.test" <<<"$command"
grep -Fq -- "--es http_url http://lab.example.test:8080/get" <<<"$command"
grep -Fq -- "--es https_url https://lab.example.test:8443/" <<<"$command"
grep -Fq -- "--es tcp_host tcp.example.test" <<<"$command"
grep -Fq -- "--ei tcp_port 9000" <<<"$command"
grep -Fq -- "--es udp_host udp.example.test" <<<"$command"
grep -Fq -- "--ei udp_port 9001" <<<"$command"
grep -Fq -- "--es relay_endpoint relay.example.test:10080" <<<"$command"
grep -Fq -- "--ez require_vpn_active false" <<<"$command"
grep -Fq -- "--ez require_proxy_ready false" <<<"$command"

echo "Feature custom probe broadcast self-test passed."

lab_env="$repo_root/test-lab/artifacts/lab-env.sh"
lab_env_backup="$(mktemp)"
warning_log="$(mktemp)"
had_lab_env=false
if [[ -f "$lab_env" ]]; then
  cp "$lab_env" "$lab_env_backup"
  had_lab_env=true
fi
cleanup() {
  if [[ "$had_lab_env" == "true" ]]; then
    cp "$lab_env_backup" "$lab_env"
  else
    rm -f "$lab_env"
  fi
  rm -f "$lab_env_backup"
  rm -f "$warning_log"
}
trap cleanup EXIT

mkdir -p "$(dirname "$lab_env")"
cat > "$lab_env" <<'EOF'
export MACBOOK_LAN_IP="198.51.100.20"
export RIPDPI_DNS_PORT="1053"
export RIPDPI_LAB_PROFILE="device"
export RIPDPI_HOST_UDP_ECHO="true"
export RIPDPI_HOST_DNS="true"
EOF

current_host="$(MACBOOK_LAN_IP= "$repo_root/test-lab/scripts/print-host-env.sh" | awk -F= '/^MACBOOK_LAN_IP=/{print $2}')"
device_command="$(
  "$repo_root/test-lab/scripts/adb-run-probe.sh" \
    --profile device \
    --mode diagnostics \
    --require-vpn-active false \
    --require-proxy-ready false \
    --print-broadcast \
    2>"$warning_log"
)"

printf '%s\n' "$device_command"
grep -Fq -- "--es lab_host $current_host" <<<"$device_command"
if grep -Fq -- "198.51.100.20" <<<"$device_command"; then
  echo "stale lab host leaked into device probe broadcast" >&2
  exit 1
fi
grep -Fq -- "Ignoring stale lab host" "$warning_log"

echo "Feature stale device probe host self-test passed."
