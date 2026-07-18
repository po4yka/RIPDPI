#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tun_device="${RIPDPI_TUN_DEVICE:-/dev/net/tun}"
evidence_path="${RIPDPI_SO_BIND_EVIDENCE_PATH:?RIPDPI_SO_BIND_EVIDENCE_PATH is required}"
ownership_path="$(dirname "$evidence_path")/ownership.env"
test_binary="${RIPDPI_SO_BIND_TEST_BINARY:?RIPDPI_SO_BIND_TEST_BINARY is required}"
: "${RIPDPI_EVIDENCE_SOURCE_SHA:?RIPDPI_EVIDENCE_SOURCE_SHA is required}"
: "${RIPDPI_EVIDENCE_RUN_ID:?RIPDPI_EVIDENCE_RUN_ID is required}"
: "${RIPDPI_EVIDENCE_RUN_ATTEMPT:?RIPDPI_EVIDENCE_RUN_ATTEMPT is required}"

write_failure_manifest() {
    local result="$1"
    local reason_code="$2"
    local cleanup_verified="$3"
    local arguments=(
        python3 "$script_dir/check_so_bindtodevice_evidence.py"
        --manifest "$evidence_path"
        --expected-source-sha "$RIPDPI_EVIDENCE_SOURCE_SHA"
        --expected-run-id "$RIPDPI_EVIDENCE_RUN_ID"
        --expected-run-attempt "$RIPDPI_EVIDENCE_RUN_ATTEMPT"
        --write-failure-result "$result"
        --reason-code "$reason_code"
    )
    if [ "$cleanup_verified" = "true" ]; then
        arguments+=(--cleanup-verified)
    fi
    "${arguments[@]}"
}

fail_infra() {
    local reason_code="$1"
    local message="$2"
    echo "$message" >&2
    write_failure_manifest INFRA_GAP "$reason_code" false
    exit 75
}

cleanup_capability_probe() {
    local status=$?
    trap - EXIT
    if [ -n "${capability_probe:-}" ]; then
        ip link del "$capability_probe" >/dev/null 2>&1 || true
        if ! link_is_absent "$capability_probe"; then
            echo "owned CAP_NET_ADMIN probe interface survived cleanup" >&2
            status=1
        fi
    fi
    exit "$status"
}

rule_priority_is_clear() {
    local family="$1"
    local priority="$2"
    local output
    if [ "$family" = "ipv6" ]; then
        output="$(ip -6 -o rule show pref "$priority")" || return 1
    else
        output="$(ip -o rule show pref "$priority")" || return 1
    fi
    [ -z "$output" ]
}

link_is_absent() {
    local expected="$1"
    local output interface
    output="$(ip -o link show)" || return 1
    while IFS= read -r line; do
        interface="${line#*: }"
        interface="${interface%%:*}"
        interface="${interface%%@*}"
        [ "$interface" != "$expected" ] || return 1
    done <<< "$output"
}

namespace_is_absent() {
    local expected="$1"
    local output namespace
    output="$(ip netns list)" || return 1
    while IFS= read -r line; do
        namespace="${line%% *}"
        [ "$namespace" != "$expected" ] || return 1
    done <<< "$output"
}

cleanup_check() {
    local status=$?
    local cleanup_verified=true
    local failure_reason=ADVERSARIAL_TEST_FAILED
    trap - EXIT
    if [ -f "$ownership_path" ]; then
        namespace="$(awk -F= '$1 == "namespace" {print $2}' "$ownership_path")"
        host_veth="$(awk -F= '$1 == "host_veth" {print $2}' "$ownership_path")"
        peer_veth="$(awk -F= '$1 == "peer_veth" {print $2}' "$ownership_path")"
        rule_priority="$(awk -F= '$1 == "rule_priority" {print $2}' "$ownership_path")"
        table="$(awk -F= '$1 == "table" {print $2}' "$ownership_path")"
        if [[ ! "$namespace" =~ ^ripdpi-uid-[0-9]+$ \
            || ! "$host_veth" =~ ^rduh[0-9]+$ \
            || ! "$peer_veth" =~ ^rdup[0-9]+$ \
            || ! "$rule_priority" =~ ^[0-9]+$ \
            || ! "$table" =~ ^[0-9]+$ ]]; then
            echo "invalid SO_BINDTODEVICE ownership descriptor" >&2
            status=1
            cleanup_verified=false
        else
            ip rule del pref "$rule_priority" oif tun0 lookup "$table" >/dev/null 2>&1 || true
            ip -6 rule del pref "$rule_priority" oif tun0 lookup "$table" >/dev/null 2>&1 || true
            if ip link show dev "$host_veth" >/dev/null 2>&1; then
                ip link del dev "$host_veth" >/dev/null 2>&1 || { status=1; cleanup_verified=false; }
            fi
            if ip link show dev "$peer_veth" >/dev/null 2>&1; then
                ip link del dev "$peer_veth" >/dev/null 2>&1 || { status=1; cleanup_verified=false; }
            fi
            if ip netns list | awk '{print $1}' | grep -qx "$namespace"; then
                ip netns del "$namespace" >/dev/null 2>&1 || { status=1; cleanup_verified=false; }
            fi
            if ! rule_priority_is_clear ipv4 "$rule_priority" \
                || ! rule_priority_is_clear ipv6 "$rule_priority" \
                || ! link_is_absent "$host_veth" \
                || ! link_is_absent "$peer_veth" \
                || ! namespace_is_absent "$namespace"; then
                echo "owned SO_BINDTODEVICE topology survived cleanup" >&2
                status=1
                cleanup_verified=false
            fi
        fi
    fi
    if ! link_is_absent tun0; then
        echo "orphaned tun0 after SO_BINDTODEVICE lane" >&2
        status=1
        cleanup_verified=false
    fi
    rm -f "$ownership_path"
    if [ "$cleanup_verified" != "true" ]; then
        failure_reason=CLEANUP_FAILED
    elif [ "$status" -eq 0 ] && [ ! -f "$evidence_path" ]; then
        status=1
        failure_reason=EVIDENCE_MISSING
    elif [ "$status" -eq 0 ] && ! python3 "$script_dir/check_so_bindtodevice_evidence.py" \
        --manifest "$evidence_path" \
        --expected-source-sha "$RIPDPI_EVIDENCE_SOURCE_SHA" \
        --expected-run-id "$RIPDPI_EVIDENCE_RUN_ID" \
        --expected-run-attempt "$RIPDPI_EVIDENCE_RUN_ATTEMPT" >/dev/null; then
        status=1
        failure_reason=EVIDENCE_INVALID
    fi
    if [ "$status" -ne 0 ]; then
        write_failure_manifest TEST_FAILURE "$failure_reason" "$cleanup_verified" || status=1
    fi
    exit "$status"
}

if [ "$(uname -s)" != "Linux" ]; then
    fail_infra PLATFORM_UNSUPPORTED "SO_BINDTODEVICE E2E requires Linux"
fi
if [ "$(id -u)" != "0" ]; then
    fail_infra ROOT_REQUIRED "SO_BINDTODEVICE E2E requires root"
fi
if [ "${RIPDPI_RUN_SO_BINDTODEVICE_E2E:-}" != "1" ]; then
    fail_infra RUN_NOT_AUTHORIZED "RIPDPI_RUN_SO_BINDTODEVICE_E2E=1 is required"
fi
if [ ! -c "$tun_device" ]; then
    fail_infra TUN_UNAVAILABLE "Linux TUN device is unavailable: $tun_device"
fi
if [[ "$test_binary" != /* ]] || [ ! -f "$test_binary" ] || [ ! -x "$test_binary" ]; then
    fail_infra TEST_BINARY_INVALID "SO_BINDTODEVICE test binary must be an absolute executable path: $test_binary"
fi
command -v ip >/dev/null || fail_infra IP_TOOL_MISSING "required command is unavailable: ip"
command -v setpriv >/dev/null || fail_infra SETPRIV_TOOL_MISSING "required command is unavailable: setpriv"
capability_probe="rdcap${BASHPID}"
if ! ip link add "$capability_probe" type dummy >/dev/null 2>&1; then
    fail_infra CAP_NET_ADMIN_UNAVAILABLE "SO_BINDTODEVICE E2E requires CAP_NET_ADMIN"
fi
trap cleanup_capability_probe EXIT
if ! ip link del "$capability_probe" >/dev/null 2>&1; then
    echo "cannot remove owned CAP_NET_ADMIN probe interface" >&2
    write_failure_manifest TEST_FAILURE CLEANUP_FAILED false
    exit 1
fi
if ! link_is_absent "$capability_probe"; then
    echo "owned CAP_NET_ADMIN probe interface survived cleanup" >&2
    write_failure_manifest TEST_FAILURE CLEANUP_FAILED false
    exit 1
fi
capability_probe=""
trap - EXIT
ipv6_sysctl_root="${RIPDPI_IPV6_SYSCTL_ROOT:-/proc/sys/net/ipv6/conf}"
for ipv6_scope in all default; do
    ipv6_disable_path="$ipv6_sysctl_root/$ipv6_scope/disable_ipv6"
    ipv6_disabled=""
    if [ ! -r "$ipv6_disable_path" ] \
        || ! IFS= read -r ipv6_disabled < "$ipv6_disable_path" \
        || [ "$ipv6_disabled" != "0" ]; then
        fail_infra IPV6_UNAVAILABLE "SO_BINDTODEVICE E2E requires IPv6 in all and default network scopes"
    fi
done
netns_snapshot="$(ip netns list)" || fail_infra TOPOLOGY_INSPECTION_FAILED "cannot inspect network namespaces"
link_snapshot="$(ip -o link show)" || fail_infra TOPOLOGY_INSPECTION_FAILED "cannot inspect network links"
ipv4_rule_snapshot="$(ip -o rule show)" || fail_infra TOPOLOGY_INSPECTION_FAILED "cannot inspect IPv4 policy rules"
ipv6_rule_snapshot="$(ip -6 -o rule show)" \
    || fail_infra IPV6_UNAVAILABLE "IPv6 policy rule operations are unavailable"
if printf '%s\n' "$netns_snapshot" | awk '{print $1}' | grep -q '^ripdpi-uid-' \
    || printf '%s\n' "$link_snapshot" | grep -Eq ': (rduh|rdup)[0-9]+(@[^:]*)?:' \
    || ! link_is_absent tun0 \
    || printf '%s\n' "$ipv4_rule_snapshot" | grep -q 'oif tun0' \
    || printf '%s\n' "$ipv6_rule_snapshot" | grep -q 'oif tun0'; then
    fail_infra DIRTY_TOPOLOGY "SO_BINDTODEVICE lane requires a clean network topology"
fi
rm -f "$evidence_path" "$ownership_path"
trap cleanup_check EXIT

mkdir -p "$(dirname "$evidence_path")"
export RIPDPI_SO_BIND_OWNERSHIP_PATH="$ownership_path"
"$test_binary" e2e_so_bindtodevice_tun_uid_guard --ignored --exact --nocapture
