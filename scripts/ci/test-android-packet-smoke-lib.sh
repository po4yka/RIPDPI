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

temp_files=()

make_temp_file() {
    local temp_file
    temp_file="$(mktemp)"
    temp_files+=("$temp_file")
    echo "$temp_file"
}

cleanup_temp_files() {
    if [[ "${#temp_files[@]}" -gt 0 ]]; then
        rm -f "${temp_files[@]}"
    fi
}

trap cleanup_temp_files EXIT

baseline_plan="$(packet_smoke_probe_plan_lines android_vpn_tunnel_baseline_family)"
assert_eq $'tcp\t1.1.1.1\t443\t5000\t5000\t' "$baseline_plan" "baseline probe plan"

doh_plan="$(packet_smoke_probe_plan_lines android_vpn_doh_family)"
assert_eq $'dns\t198.18.0.53\t53\t0\t5000\texample.com' "$doh_plan" "doh probe plan"

autolearn_lines="$(packet_smoke_probe_plan_lines android_vpn_host_autolearn_family | wc -l | tr -d ' ')"
assert_eq "2" "$autolearn_lines" "autolearn probe count"

assert_status 1 "unsupported scenario fails" packet_smoke_probe_plan_lines android_proxy_tlsrec_family

assert_status 0 "physical indirect supports adb observable" \
    packet_smoke_required_capability_supported adb_observable physical_indirect
assert_status 1 "physical indirect rejects raw pcap" \
    packet_smoke_required_capability_supported adb_raw_pcap physical_indirect
assert_status 0 "rooted capture supports raw pcap" \
    packet_smoke_required_capability_supported adb_raw_pcap rooted_android_pcap
assert_status 0 "emulator raw supports raw pcap" \
    packet_smoke_required_capability_supported adb_raw_pcap emulator_raw
assert_status 1 "unknown capability is unsupported" \
    packet_smoke_required_capability_supported adb_magic physical_indirect
assert_status 0 "one executed scenario satisfies the runner" \
    packet_smoke_require_executed_scenarios 1 android_proxy_split_host_plus_one
assert_status 1 "filtered unsupported scenario fails instead of silently passing" \
    packet_smoke_require_executed_scenarios 0 android_proxy_split_host_plus_one

instrumentation_success="$(make_temp_file)"
cat >"$instrumentation_success" <<'EOF'
INSTRUMENTATION_STATUS: class=com.poyka.ripdpi.e2e.PacketSmokeInstrumentedTest
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS_CODE: 0
OK (1 test)
INSTRUMENTATION_CODE: -1
EOF
assert_status 1 "successful instrumentation is not failed" packet_smoke_instrumentation_output_failed "$instrumentation_success"
assert_status 0 "successful instrumentation completed" packet_smoke_instrumentation_output_completed "$instrumentation_success"

instrumentation_failure="$(make_temp_file)"
cat >"$instrumentation_failure" <<'EOF'
INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: expected true
INSTRUMENTATION_STATUS_CODE: -2
FAILURES!!!
INSTRUMENTATION_CODE: -1
EOF
assert_status 0 "failed instrumentation is failed" packet_smoke_instrumentation_output_failed "$instrumentation_failure"
assert_status 0 "failed instrumentation still completed" packet_smoke_instrumentation_output_completed "$instrumentation_failure"

instrumentation_crash="$(make_temp_file)"
cat >"$instrumentation_crash" <<'EOF'
INSTRUMENTATION_RESULT: shortMsg=Process crashed.
INSTRUMENTATION_CODE: 0
EOF
assert_status 0 "crashed instrumentation is failed" packet_smoke_instrumentation_output_failed "$instrumentation_crash"
assert_status 1 "crashed instrumentation did not complete" packet_smoke_instrumentation_output_completed "$instrumentation_crash"

echo "android packet smoke lib tests passed"
