#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"
registry="$repo_root/scripts/ci/packet-smoke-scenarios.json"
generator="$repo_root/scripts/ci/packet-smoke-generator.py"

artifact_root="${RIPDPI_PACKET_SMOKE_ARTIFACT_DIR:-$repo_root/build/packet-smoke/cli}"
scenario_filter="${RIPDPI_PACKET_SMOKE_SCENARIO_FILTER:-}"
capture_mode="${RIPDPI_PACKET_SMOKE_CAPTURE_MODE:-auto}"
tcpdump_bin="${RIPDPI_PACKET_SMOKE_TCPDUMP_BIN:-tcpdump}"
tshark_bin="${RIPDPI_PACKET_SMOKE_TSHARK_BIN:-tshark}"
generated_mode="${RIPDPI_PACKET_SMOKE_GENERATED_MODE:-}"
generated_budget="${RIPDPI_PACKET_SMOKE_GENERATED_BUDGET:-}"
generated_seed="${RIPDPI_PACKET_SMOKE_GENERATED_SEED:-}"
generated_enabled="${RIPDPI_PACKET_SMOKE_GENERATED:-auto}"

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
require_command python3
require_command "$tcpdump_bin"
require_command "$tshark_bin"
preflight_capture_tools

mkdir -p "$artifact_root"

default_generated_mode() {
    if [[ -n "$generated_mode" ]]; then
        printf '%s\n' "$generated_mode"
    elif [[ "${GITHUB_EVENT_NAME:-}" == "schedule" ]]; then
        printf 'nightly\n'
    else
        printf 'pr\n'
    fi
}

default_generated_budget() {
    local mode="$1"
    if [[ -n "$scenario_filter" || "$generated_enabled" == "0" || "$generated_enabled" == "false" ]]; then
        printf '0\n'
    elif [[ -n "$generated_budget" ]]; then
        printf '%s\n' "$generated_budget"
    elif [[ "$mode" == "nightly" ]]; then
        printf '64\n'
    else
        printf '8\n'
    fi
}

default_generated_seed() {
    if [[ -n "$generated_seed" ]]; then
        printf '%s\n' "$generated_seed"
    else
        printf '%s:%s:%s\n' \
            "${GITHUB_EVENT_NAME:-local}" \
            "${GITHUB_REF:-local}" \
            "${GITHUB_SHA:-$(git -C "$repo_root" rev-parse HEAD 2>/dev/null || printf 'unknown')}"
    fi
}

run_scenario() {
    local scenario_id="$1"
    local test_selector="$2"
    local generator_metadata="${3:-}"
    local scenario_dir="$artifact_root/$scenario_id"
    rm -rf "$scenario_dir"
    mkdir -p "$scenario_dir"

    echo "==> CLI packet smoke: $scenario_id"
    if [[ -n "$generator_metadata" ]]; then
        RIPDPI_RUN_PACKET_SMOKE=1 \
        RIPDPI_PACKET_SMOKE_CAPTURE_MODE="$capture_mode" \
        RIPDPI_PACKET_SMOKE_ARTIFACT_DIR="$scenario_dir" \
        RIPDPI_PACKET_SMOKE_TCPDUMP_BIN="$tcpdump_bin" \
        RIPDPI_PACKET_SMOKE_TSHARK_BIN="$tshark_bin" \
        RIPDPI_PACKET_SMOKE_GENERATOR_METADATA="$generator_metadata" \
        cargo test \
            --manifest-path "$workspace_manifest" \
            -p ripdpi-cli \
            --test packet_smoke \
            "$test_selector" \
            -- \
            --exact \
            --nocapture 2>&1 | tee "$scenario_dir/test-output.txt"
    else
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
    fi
}

jq_selector='.[] | select(.lane == "cli" and (.generatedTemplate != true))'
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
    run_scenario "$scenario_id" "$test_selector"
done

mode="$(default_generated_mode)"
budget="$(default_generated_budget "$mode")"
seed="$(default_generated_seed)"
if ! [[ "$budget" =~ ^[0-9]+$ ]]; then
    echo "RIPDPI_PACKET_SMOKE_GENERATED_BUDGET must be a non-negative integer, got: $budget" >&2
    exit 1
fi
if [[ "$budget" -gt 0 ]]; then
    generated_origin="random"
    generated_plan="$artifact_root/generated-scenarios.json"
    python3 "$generator" --registry "$registry" --seed "$seed" --budget "$budget" --origin "$generated_origin" >"$generated_plan"
    echo "==> CLI packet smoke generated sample: mode=$mode origin=$generated_origin budget=$budget seed=$seed"
    mapfile -t generated_scenarios < <(jq -r '.[] | [.id, .testSelector, (. | tojson)] | @tsv' "$generated_plan")
    for row in "${generated_scenarios[@]}"; do
        IFS=$'\t' read -r scenario_id test_selector generator_metadata <<<"$row"
        run_scenario "$scenario_id" "$test_selector" "$generator_metadata"
    done
fi
