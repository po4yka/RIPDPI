#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"
tun_device="${RIPDPI_TUN_DEVICE:-/dev/net/tun}"
evidence_path="${RIPDPI_SO_BIND_EVIDENCE_PATH:?RIPDPI_SO_BIND_EVIDENCE_PATH is required}"
ownership_path="$(dirname "$evidence_path")/ownership.env"
source_sha="${RIPDPI_EVIDENCE_SOURCE_SHA:?RIPDPI_EVIDENCE_SOURCE_SHA is required}"
run_id="${RIPDPI_EVIDENCE_RUN_ID:?RIPDPI_EVIDENCE_RUN_ID is required}"
run_attempt="${RIPDPI_EVIDENCE_RUN_ATTEMPT:?RIPDPI_EVIDENCE_RUN_ATTEMPT is required}"

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
    status=$?
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
        else
            ip rule del pref "$rule_priority" oif tun0 lookup "$table" >/dev/null 2>&1 || true
            ip -6 rule del pref "$rule_priority" oif tun0 lookup "$table" >/dev/null 2>&1 || true
            if ip link show dev "$host_veth" >/dev/null 2>&1; then
                ip link del dev "$host_veth" >/dev/null 2>&1 || status=1
            fi
            if ip link show dev "$peer_veth" >/dev/null 2>&1; then
                ip link del dev "$peer_veth" >/dev/null 2>&1 || status=1
            fi
            if ip netns list | awk '{print $1}' | grep -qx "$namespace"; then
                ip netns del "$namespace" >/dev/null 2>&1 || status=1
            fi
            if ! rule_priority_is_clear ipv4 "$rule_priority" \
                || ! rule_priority_is_clear ipv6 "$rule_priority" \
                || ! link_is_absent "$host_veth" \
                || ! link_is_absent "$peer_veth" \
                || ! namespace_is_absent "$namespace"; then
                echo "owned SO_BINDTODEVICE topology survived cleanup" >&2
                status=1
            fi
        fi
    fi
    if ! link_is_absent tun0; then
        echo "orphaned tun0 after SO_BINDTODEVICE lane" >&2
        status=1
    fi
    rm -f "$ownership_path"
    exit "$status"
}

if [ "$(uname -s)" != "Linux" ]; then
    echo "SO_BINDTODEVICE E2E requires Linux" >&2
    exit 1
fi
if [ "$(id -u)" != "0" ]; then
    echo "SO_BINDTODEVICE E2E requires root/CAP_NET_ADMIN" >&2
    exit 1
fi
if [ "${RIPDPI_RUN_SO_BINDTODEVICE_E2E:-}" != "1" ]; then
    echo "RIPDPI_RUN_SO_BINDTODEVICE_E2E=1 is required" >&2
    exit 1
fi
if [ ! -c "$tun_device" ]; then
    echo "Linux TUN device is unavailable: $tun_device" >&2
    exit 1
fi
for command in cargo ip python3; do
    command -v "$command" >/dev/null || { echo "required command is unavailable: $command" >&2; exit 1; }
done
netns_snapshot="$(ip netns list)" || { echo "cannot inspect network namespaces" >&2; exit 1; }
link_snapshot="$(ip -o link show)" || { echo "cannot inspect network links" >&2; exit 1; }
ipv4_rule_snapshot="$(ip -o rule show)" || { echo "cannot inspect IPv4 policy rules" >&2; exit 1; }
ipv6_rule_snapshot="$(ip -6 -o rule show)" || { echo "cannot inspect IPv6 policy rules" >&2; exit 1; }
if printf '%s\n' "$netns_snapshot" | awk '{print $1}' | grep -q '^ripdpi-uid-' \
    || printf '%s\n' "$link_snapshot" | grep -Eq ': (rduh|rdup)[0-9]+(@[^:]*)?:' \
    || ! link_is_absent tun0 \
    || printf '%s\n' "$ipv4_rule_snapshot" | grep -q 'oif tun0' \
    || printf '%s\n' "$ipv6_rule_snapshot" | grep -q 'oif tun0'; then
    echo "SO_BINDTODEVICE lane requires a clean network topology" >&2
    exit 1
fi
rm -f "$evidence_path" "$ownership_path"
trap cleanup_check EXIT

target_exists="$(python3 - "$workspace_manifest" <<'PY'
import json
import subprocess
import sys

metadata = json.loads(subprocess.run(
    ["cargo", "metadata", "--locked", "--manifest-path", sys.argv[1], "--format-version", "1", "--no-deps"],
    check=True, capture_output=True, text=True,
).stdout)
print("yes" if any(
    package["name"] == "ripdpi-tunnel-core"
    and any(target["name"] == "so_bindtodevice_e2e" and "test" in target["kind"] for target in package["targets"])
    for package in metadata["packages"]
) else "no")
PY
)"
if [ "$target_exists" != "yes" ]; then
    echo "so_bindtodevice_e2e target is not present" >&2
    exit 1
fi

mkdir -p "$(dirname "$evidence_path")"
export RIPDPI_SO_BIND_OWNERSHIP_PATH="$ownership_path"
cargo test --locked --manifest-path "$workspace_manifest" -p ripdpi-tunnel-core \
    --test so_bindtodevice_e2e e2e_so_bindtodevice_tun_uid_guard -- --ignored --exact --nocapture

python3 "$repo_root/scripts/ci/check_so_bindtodevice_evidence.py" \
    --manifest "$evidence_path" \
    --expected-source-sha "$source_sha" \
    --expected-run-id "$run_id" \
    --expected-run-attempt "$run_attempt"
