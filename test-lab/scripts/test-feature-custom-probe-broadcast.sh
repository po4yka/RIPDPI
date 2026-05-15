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
