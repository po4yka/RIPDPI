#!/usr/bin/env bash

packet_smoke_probe_plan_lines() {
    local scenario_id="${1:-}"
    case "$scenario_id" in
        android_vpn_tunnel_baseline_family)
            printf 'tcp\t1.1.1.1\t443\t5000\t5000\t\n'
            ;;
        android_vpn_doh_family|android_vpn_dot_family|android_vpn_dnscrypt_family|android_vpn_doq_family|android_vpn_doh_fault_family|android_vpn_dot_fault_family|android_vpn_dnscrypt_fault_family|android_vpn_doq_fault_family)
            printf 'dns\t198.18.0.53\t53\t0\t5000\texample.com\n'
            ;;
        android_vpn_host_autolearn_family|android_vpn_remembered_policy_family)
            printf 'tcp\texample.com\t443\t5000\t5000\t\n'
            printf 'tcp\texample.com\t443\t5000\t5000\t\n'
            ;;
        android_vpn_ws_tunnel_fallback_family)
            printf 'tcp\texample.com\t443\t5000\t5000\t\n'
            ;;
        *)
            return 1
            ;;
    esac
}

packet_smoke_required_capability_supported() {
    local required_capability="${1:-}"
    local device_profile="${2:-}"
    case "$required_capability" in
        ""|adb_observable)
            return 0
            ;;
        adb_raw_pcap)
            [[ "$device_profile" != "physical_indirect" ]]
            ;;
        adb_raw_pcap_ipv6)
            [[ "$device_profile" != "physical_indirect" && "${fixture_android_host:-}" == *:* ]]
            ;;
        *)
            return 1
            ;;
    esac
}

packet_smoke_require_executed_scenarios() {
    local executed_count="${1:-0}"
    local scenario_filter="${2:-}"
    if [[ "$executed_count" -eq 0 ]]; then
        echo "No Android packet smoke scenario executed for filter: ${scenario_filter:-<all>}" >&2
        return 1
    fi
}

packet_smoke_instrumentation_output_failed() {
    local output_file="$1"
    grep -Eq '(^FAILURES!!!$|^INSTRUMENTATION_STATUS_CODE: -2$|^INSTRUMENTATION_RESULT: shortMsg=|^INSTRUMENTATION_CODE: 0$|Process crashed)' "$output_file"
}

packet_smoke_instrumentation_output_completed() {
    local output_file="$1"
    grep -Eq '^INSTRUMENTATION_CODE: -1$' "$output_file"
}
