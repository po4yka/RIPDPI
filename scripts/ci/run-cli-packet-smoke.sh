#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"
registry="$repo_root/scripts/ci/packet-smoke-scenarios.json"

artifact_root="${RIPDPI_PACKET_SMOKE_ARTIFACT_DIR:-$repo_root/build/packet-smoke/cli}"
scenario_filter="${RIPDPI_PACKET_SMOKE_SCENARIO_FILTER:-}"
capture_mode="${RIPDPI_PACKET_SMOKE_CAPTURE_MODE:-auto}"
tcpdump_bin="${RIPDPI_PACKET_SMOKE_TCPDUMP_BIN:-tcpdump}"
tshark_bin="${RIPDPI_PACKET_SMOKE_TSHARK_BIN:-tshark}"

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "Missing required command: $1" >&2
        exit 1
    fi
}

preflight_capture_tools() {
    if [[ "$capture_mode" == "indirect" ]]; then
        echo "CLI packet smoke requires raw host capture; set RIPDPI_PACKET_SMOKE_CAPTURE_MODE=auto or raw." >&2
        exit 1
    fi
    if ! "$tcpdump_bin" -D >/dev/null 2>&1; then
        cat >&2 <<EOF
tcpdump is present but not usable for CLI packet smoke.
Grant capture permissions first, for example on Linux CI:
  sudo setcap cap_net_raw,cap_net_admin=eip "$(command -v "$tcpdump_bin")"
EOF
        exit 1
    fi
    "$tshark_bin" --version >/dev/null 2>&1
}

require_command cargo
require_command jq
require_command "$tcpdump_bin"
require_command "$tshark_bin"
preflight_capture_tools

mkdir -p "$artifact_root"

jq_selector='.[] | select(.lane == "cli")'
if [[ -n "$scenario_filter" ]]; then
    jq_selector+=' | select(.id == $scenario_filter or .testSelector == $scenario_filter)'
fi

mapfile -t scenarios < <(jq -r --arg scenario_filter "$scenario_filter" "$jq_selector | [.id, .testSelector] | @tsv" "$registry")

if [[ "${#scenarios[@]}" -eq 0 ]]; then
    echo "No CLI packet smoke scenarios matched filter: ${scenario_filter:-<all>}" >&2
    exit 1
fi

for row in "${scenarios[@]}"; do
    IFS=$'\t' read -r scenario_id test_selector <<<"$row"
    scenario_dir="$artifact_root/$scenario_id"
    rm -rf "$scenario_dir"
    mkdir -p "$scenario_dir"

    echo "==> CLI packet smoke: $scenario_id"
    RIPDPI_RUN_PACKET_SMOKE=1 \
    RIPDPI_PACKET_SMOKE_CAPTURE_MODE="$capture_mode" \
    RIPDPI_PACKET_SMOKE_ARTIFACT_DIR="$scenario_dir" \
    RIPDPI_PACKET_SMOKE_TCPDUMP_BIN="$tcpdump_bin" \
    RIPDPI_PACKET_SMOKE_TSHARK_BIN="$tshark_bin" \
    cargo test \
        --manifest-path "$workspace_manifest" \
        -p ripdpi-cli \
        --test packet_smoke \
        "$test_selector" \
        -- \
        --exact \
        --nocapture 2>&1 | tee "$scenario_dir/test-output.txt"
done
