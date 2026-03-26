#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
source "$repo_root/scripts/ci/android-packet-smoke-lib.sh"

assert_eq() {
    local expected="$1"
    local actual="$2"
    local label="$3"
    if [[ "$expected" != "$actual" ]]; then
        echo "assertion failed for $label" >&2
        echo "expected: $expected" >&2
        echo "actual:   $actual" >&2
        exit 1
    fi
}

assert_status() {
    local expected="$1"
    local label="$2"
    shift 2
    set +e
    "$@"
    local status=$?
    set -e
    if [[ "$status" -ne "$expected" ]]; then
        echo "assertion failed for $label" >&2
        echo "expected status: $expected" >&2
        echo "actual status:   $status" >&2
        exit 1
    fi
}

baseline_plan="$(packet_smoke_probe_plan_lines android_vpn_tunnel_baseline_family)"
assert_eq $'tcp\t1.1.1.1\t443\t5000\t5000\t' "$baseline_plan" "baseline probe plan"

doh_plan="$(packet_smoke_probe_plan_lines android_vpn_doh_family)"
assert_eq $'dns\t198.18.0.53\t53\t0\t5000\texample.com' "$doh_plan" "doh probe plan"

autolearn_lines="$(packet_smoke_probe_plan_lines android_vpn_host_autolearn_family | wc -l | tr -d ' ')"
assert_eq "2" "$autolearn_lines" "autolearn probe count"

assert_status 1 "unsupported scenario fails" packet_smoke_probe_plan_lines android_proxy_tlsrec_family

echo "android packet smoke lib tests passed"
