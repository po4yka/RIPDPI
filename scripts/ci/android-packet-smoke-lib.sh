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
