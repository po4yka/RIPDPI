#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
registry="$repo_root/scripts/ci/packet-smoke-scenarios.json"
start_fixture_script="$repo_root/scripts/ci/start-local-network-fixture.sh"
stop_fixture_script="$repo_root/scripts/ci/stop-local-network-fixture.sh"
runner_lib="$repo_root/scripts/ci/android-packet-smoke-lib.sh"

source "$runner_lib"

artifact_root="${RIPDPI_PACKET_SMOKE_ARTIFACT_DIR:-$repo_root/build/packet-smoke/android}"
scenario_filter="${RIPDPI_PACKET_SMOKE_SCENARIO_FILTER:-}"
capture_mode="${RIPDPI_PACKET_SMOKE_CAPTURE_MODE:-auto}"
fixture_android_host_override="${RIPDPI_FIXTURE_ANDROID_HOST:-}"
gradle_abi_override="${RIPDPI_LOCAL_NATIVE_ABI:-}"
android_serial="${ANDROID_SERIAL:-}"
app_package="com.poyka.ripdpi"
debug_probe_component="com.poyka.ripdpi/.debug.DebugNetworkProbeReceiver"

fixture_pid_file=""
fixture_manifest=""
fixture_control_port=""
fixture_android_host=""
selected_capture_mode=""
device_profile=""
device_capture_filter=""
is_emulator="0"
instrumentation_component=""

adb_cmd() {
    if [[ -n "$android_serial" ]]; then
        adb -s "$android_serial" "$@"
    else
        adb "$@"
    fi
}

shell_quote() {
    printf "'%s'" "${1//\'/\'\"\'\"\'}"
}

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "Missing required command: $1" >&2
        exit 1
    fi
}

package_is_installed() {
    adb_cmd shell pm path "$1" >/dev/null 2>&1
}

cleanup() {
    if [[ -n "$fixture_pid_file" ]]; then
        bash "$stop_fixture_script" "$fixture_pid_file" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

detect_emulator() {
    local qemu
    qemu="$(adb_cmd shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')"
    if [[ "$qemu" == "1" ]]; then
        is_emulator="1"
    fi
}

resolve_fixture_android_host() {
    if [[ -n "$fixture_android_host_override" ]]; then
        echo "$fixture_android_host_override"
    elif [[ "$is_emulator" == "1" ]]; then
        echo "10.0.2.2"
    else
        echo "127.0.0.1"
    fi
}

reverse_fixture_ports() {
    if [[ "$fixture_android_host" != "127.0.0.1" ]]; then
        return
    fi
    mapfile -t tcp_ports < <(
        jq -r '[.controlPort, .tcpEchoPort, .tlsEchoPort, .dnsHttpPort, .dnsDotPort, .dnsDnscryptPort, .socks5Port] | .[]' "$fixture_manifest"
    )
    for port in "${tcp_ports[@]}"; do
        adb_cmd reverse "tcp:${port}" "tcp:${port}" >/dev/null
    done
}

detect_capture_mode() {
    if [[ "$capture_mode" == "indirect" ]]; then
        selected_capture_mode="indirect"
        return
    fi

    local root_ready="0"
    if adb_cmd root >/dev/null 2>&1; then
        adb_cmd wait-for-device >/dev/null 2>&1 || true
        if adb_cmd shell id 2>/dev/null | tr -d '\r' | grep -q 'uid=0'; then
            root_ready="1"
        fi
    fi

    if [[ "$root_ready" == "1" ]] && adb_cmd shell 'command -v tcpdump >/dev/null 2>&1' >/dev/null 2>&1; then
        selected_capture_mode="raw"
        return
    fi

    if [[ "$capture_mode" == "raw" ]]; then
        echo "Raw Android packet capture was requested, but adb root or on-device tcpdump is unavailable." >&2
        exit 1
    fi

    selected_capture_mode="indirect"
}

detect_device_profile() {
    if [[ "$is_emulator" == "1" ]]; then
        device_profile="emulator_raw"
    elif [[ "$selected_capture_mode" == "raw" ]]; then
        device_profile="rooted_android_pcap"
    else
        device_profile="physical_indirect"
    fi
}

detect_gradle_abi() {
    if [[ -n "$gradle_abi_override" ]]; then
        echo "$gradle_abi_override"
        return
    fi
    local abi
    abi="$(adb_cmd shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
    case "$abi" in
        x86_64|x86)
            echo "x86_64"
            ;;
        *)
            echo "arm64-v8a"
            ;;
    esac
}

collect_device_snapshot() {
    local scenario_dir="$1"
    adb_cmd logcat -d >"$scenario_dir/logcat.txt" 2>&1 || true
    adb_cmd shell dumpsys connectivity >"$scenario_dir/dumpsys-connectivity.txt" 2>&1 || true
    adb_cmd shell ip addr >"$scenario_dir/ip-addr.txt" 2>&1 || adb_cmd shell ifconfig >"$scenario_dir/ip-addr.txt" 2>&1 || true
    adb_cmd shell ip route >"$scenario_dir/ip-route.txt" 2>&1 || true
}

start_device_capture() {
    local scenario_id="$1"
    if [[ "$selected_capture_mode" != "raw" ]]; then
        return
    fi

    local remote_pcap="/data/local/tmp/${scenario_id}.pcap"
    local remote_log="/data/local/tmp/${scenario_id}.tcpdump.log"
    adb_cmd shell "rm -f '$remote_pcap' '$remote_log'; nohup tcpdump -i any -U -n -s 0 -w '$remote_pcap' \"$device_capture_filter\" >'$remote_log' 2>&1 & echo \$!"
}

stop_device_capture() {
    local scenario_id="$1"
    local scenario_dir="$2"
    local pid="${3:-}"
    local remote_pcap="/data/local/tmp/${scenario_id}.pcap"
    local remote_log="/data/local/tmp/${scenario_id}.tcpdump.log"

    if [[ "$selected_capture_mode" == "raw" && -n "$pid" ]]; then
        adb_cmd shell "kill -INT $pid" >/dev/null 2>&1 || true
        sleep 1
        adb_cmd pull "$remote_pcap" "$scenario_dir/device-capture.pcap" >/dev/null 2>&1 || true
        adb_cmd shell "cat '$remote_log'" >"$scenario_dir/device-capture.log" 2>&1 || true
        adb_cmd shell "rm -f '$remote_pcap' '$remote_log'" >/dev/null 2>&1 || true
    fi
}

resolve_instrumentation_component() {
    adb_cmd shell pm list instrumentation | tr -d '\r' | awk '
        /target=com\.poyka\.ripdpi/ && $0 !~ /baselineprofile/ {
            sub(/^instrumentation:/, "", $0)
            sub(/ .*/, "", $0)
            print
            exit
        }
    '
}

require_run_as() {
    if ! adb_cmd shell "run-as $app_package true" >/dev/null 2>&1; then
        echo "run-as is unavailable for $app_package. Physical indirect packet smoke requires a debuggable install." >&2
        exit 1
    fi
}

fail_if_existing_install_blocks_physical_runner() {
    if ! package_is_installed "$app_package"; then
        return
    fi
    if adb_cmd shell "run-as $app_package true" >/dev/null 2>&1; then
        return
    fi
    cat >&2 <<EOF
Physical indirect packet smoke requires the repo's debuggable install for $app_package.
An existing non-debuggable install is present on the device, so runner-driven probe state cannot be read with run-as.
Uninstall $app_package and $app_package.test from the device, then rerun this script.
EOF
    exit 1
}

install_physical_indirect_builds() {
    local install_log
    local status
    install_log="$(mktemp)"
    set +e
    ANDROID_SERIAL="$android_serial" \
    ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:installDebug :app:installDebugAndroidTest \
        "-Pripdpi.localNativeAbis=${gradle_abi}" >"$install_log" 2>&1
    status=$?
    set -e
    if [[ "$status" -eq 0 ]]; then
        rm -f "$install_log"
        return 0
    fi
    if grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' "$install_log"; then
        cat >&2 <<EOF
Physical indirect packet smoke could not install the local debug build because the device already has $app_package installed with a different signing key.
Uninstall $app_package and $app_package.test from the device, then rerun this script.
EOF
    fi
    cat "$install_log" >&2
    rm -f "$install_log"
    return "$status"
}

clear_app_packet_smoke_state() {
    local scenario_id="$1"
    adb_cmd shell "run-as $app_package rm -rf $(shell_quote "cache/packet-smoke/$scenario_id")" >/dev/null 2>&1 || true
}

pull_app_private_file() {
    local relative_path="$1"
    local destination="$2"
    local tmp_file="${destination}.tmp"
    if adb_cmd exec-out run-as "$app_package" cat "$relative_path" >"$tmp_file" 2>/dev/null; then
        mv "$tmp_file" "$destination"
        return 0
    fi
    rm -f "$tmp_file"
    return 1
}

wait_for_app_private_file() {
    local relative_path="$1"
    local destination="$2"
    local attempts="${3:-20}"
    local sleep_seconds="${4:-1}"
    local try
    for ((try = 0; try < attempts; try += 1)); do
        if pull_app_private_file "$relative_path" "$destination"; then
            return 0
        fi
        sleep "$sleep_seconds"
    done
    return 1
}

append_runner_log() {
    local log_file="$1"
    shift
    printf '%s\n' "$*" | tee -a "$log_file"
}

run_remote_command_logged() {
    local log_file="$1"
    local command="$2"
    set +e
    adb_cmd shell "$command" 2>&1 | tr -d '\r' | tee -a "$log_file"
    local status=${PIPESTATUS[0]}
    set -e
    return "$status"
}

run_instrumentation_phase() {
    local scenario_id="$1"
    local test_selector="$2"
    local phase="$3"
    local log_file="$4"
    local command
    printf -v command \
        "am instrument -w -r -e class %s -e ripdpi.fixtureControlHost %s -e ripdpi.fixtureControlPort %s -e ripdpi.packetSmokeDeviceProfile %s -e ripdpi.packetSmokePhase %s -e ripdpi.packetSmokeScenarioId %s %s" \
        "$(shell_quote "$test_selector")" \
        "$(shell_quote "$fixture_android_host")" \
        "$(shell_quote "$fixture_control_port")" \
        "$(shell_quote "$device_profile")" \
        "$(shell_quote "$phase")" \
        "$(shell_quote "$scenario_id")" \
        "$(shell_quote "$instrumentation_component")"
    append_runner_log "$log_file" "==> instrumentation phase: $phase"
    run_remote_command_logged "$log_file" "$command"
}

append_probe_result_artifact() {
    local runner_probe_json="$1"
    local result_json="$2"
    local tmp_file="${runner_probe_json}.tmp"
    jq --slurpfile result "$result_json" '.results += $result' "$runner_probe_json" >"$tmp_file"
    mv "$tmp_file" "$runner_probe_json"
}

record_single_phase_vpn_artifacts() {
    local scenario_id="$1"
    local scenario_dir="$2"
    jq -n --arg scenario_id "$scenario_id" --arg device_profile "$device_profile" \
        '{scenarioId:$scenario_id, deviceProfile:$device_profile, mode:"single-phase"}' \
        >"$scenario_dir/prepare-state.json"
    jq -n --arg scenario_id "$scenario_id" --arg device_profile "$device_profile" \
        '{scenarioId:$scenario_id, deviceProfile:$device_profile, mode:"single-phase", results:[]}' \
        >"$scenario_dir/runner-probe.json"
    printf 'single-phase instrumentation path; runner-driven probe not used\n' >"$scenario_dir/runner-probe-command.txt"
}

run_probe_broadcast() {
    local scenario_id="$1"
    local command_log="$2"
    local combined_log="$3"
    local runner_probe_json="$4"
    shift 4
    local request_id="${scenario_id}-$(date +%s%N)"
    local command="$* --es scenario_id $(shell_quote "$scenario_id") --es request_id $(shell_quote "$request_id")"
    local relative_probe_path="cache/packet-smoke/$scenario_id/probe-result.json"
    local temp_result
    temp_result="$(mktemp)"
    printf '%s\n' "$command" >>"$command_log"
    if ! run_remote_command_logged "$combined_log" "$command"; then
        rm -f "$temp_result"
        return 1
    fi
    if ! wait_for_app_private_file "$relative_probe_path" "$temp_result"; then
        echo "Timed out waiting for runner probe result at $relative_probe_path" | tee -a "$combined_log" >&2
        rm -f "$temp_result"
        return 1
    fi
    append_probe_result_artifact "$runner_probe_json" "$temp_result"
    rm -f "$temp_result"
}

run_physical_runner_probe() {
    local scenario_id="$1"
    local traffic_kind="$2"
    local scenario_dir="$3"
    local command_log="$scenario_dir/runner-probe-command.txt"
    local combined_log="$scenario_dir/test-output.txt"
    local runner_probe_json="$scenario_dir/runner-probe.json"
    local -a probe_plan=()
    local plan_line
    local probe_type
    local host
    local port
    local connect_timeout_ms
    local read_timeout_ms
    local query_host
    jq -n --arg scenario_id "$scenario_id" --arg device_profile "$device_profile" \
        '{scenarioId:$scenario_id, deviceProfile:$device_profile, results:[]}' \
        >"$runner_probe_json"
    : >"$command_log"

    if ! mapfile -t probe_plan < <(packet_smoke_probe_plan_lines "$scenario_id"); then
        echo "Unsupported physical-indirect VPN scenario: $scenario_id ($traffic_kind)" >&2
        return 1
    fi
    if [[ "${#probe_plan[@]}" -eq 0 ]]; then
        echo "Missing runner probe plan for physical-indirect VPN scenario: $scenario_id ($traffic_kind)" >&2
        return 1
    fi

    for plan_line in "${probe_plan[@]}"; do
        IFS=$'\t' read -r probe_type host port connect_timeout_ms read_timeout_ms query_host <<<"$plan_line"
        local broadcast_action
        local command
        case "$probe_type" in
            tcp)
                broadcast_action="com.poyka.ripdpi.debug.PROBE_TCP"
                printf -v command \
                    "am broadcast -a %s -n %s --es host %s --ei port %s --ei connect_timeout_ms %s --ei read_timeout_ms %s" \
                    "$(shell_quote "$broadcast_action")" \
                    "$(shell_quote "$debug_probe_component")" \
                    "$(shell_quote "$host")" \
                    "$(shell_quote "$port")" \
                    "$(shell_quote "$connect_timeout_ms")" \
                    "$(shell_quote "$read_timeout_ms")"
                ;;
            dns)
                broadcast_action="com.poyka.ripdpi.debug.PROBE_DNS"
                printf -v command \
                    "am broadcast -a %s -n %s --es host %s --ei port %s --es query_host %s --ei read_timeout_ms %s" \
                    "$(shell_quote "$broadcast_action")" \
                    "$(shell_quote "$debug_probe_component")" \
                    "$(shell_quote "$host")" \
                    "$(shell_quote "$port")" \
                    "$(shell_quote "$query_host")" \
                    "$(shell_quote "$read_timeout_ms")"
                ;;
            *)
                echo "Unsupported runner probe type for $scenario_id: $probe_type" >&2
                return 1
                ;;
        esac
        run_probe_broadcast \
            "$scenario_id" \
            "$command_log" \
            "$combined_log" \
            "$runner_probe_json" \
            "$command"
    done
}

run_physical_indirect_vpn_scenario() {
    local scenario_id="$1"
    local test_selector="$2"
    local traffic_kind="$3"
    local scenario_dir="$4"
    local prepare_state_relative="cache/packet-smoke/$scenario_id/prepare-state.json"

    clear_app_packet_smoke_state "$scenario_id"
    jq -n --arg scenario_id "$scenario_id" --arg device_profile "$device_profile" \
        '{scenarioId:$scenario_id, deviceProfile:$device_profile, results:[]}' \
        >"$scenario_dir/runner-probe.json"
    : >"$scenario_dir/runner-probe-command.txt"
    if ! run_instrumentation_phase "$scenario_id" "$test_selector" "prepare" "$scenario_dir/test-output.txt"; then
        return 1
    fi
    if ! wait_for_app_private_file "$prepare_state_relative" "$scenario_dir/prepare-state.json"; then
        echo "Missing prepare-state.json after prepare phase for $scenario_id" | tee -a "$scenario_dir/test-output.txt" >&2
        return 1
    fi
    append_runner_log "$scenario_dir/test-output.txt" "==> runner probe"
    if ! run_physical_runner_probe "$scenario_id" "$traffic_kind" "$scenario_dir"; then
        return 1
    fi
    if ! run_instrumentation_phase "$scenario_id" "$test_selector" "assert" "$scenario_dir/test-output.txt"; then
        return 1
    fi
    return 0
}

scenario_is_supported() {
    local traffic_kind="$1"
    : "$traffic_kind"
    return 0
}

require_command adb
require_command curl
require_command jq

mkdir -p "$artifact_root"
detect_emulator
fixture_android_host="$(resolve_fixture_android_host)"
detect_capture_mode
detect_device_profile

shared_dir="$artifact_root/shared"
mkdir -p "$shared_dir"
fixture_log="$shared_dir/fixture.log"
fixture_manifest="$shared_dir/fixture-manifest.json"
fixture_pid_file="$shared_dir/fixture.pid"

RIPDPI_FIXTURE_ANDROID_HOST="$fixture_android_host" bash "$start_fixture_script" "$fixture_log" "$fixture_manifest" "$fixture_pid_file" >/dev/null
fixture_control_port="$(jq -r '.controlPort' "$fixture_manifest")"
device_capture_filter="$(jq -r '[.tcpEchoPort, .tlsEchoPort, .dnsHttpPort, .dnsDotPort, .dnsDnscryptPort, .dnsDoqPort] | map("port " + tostring) | join(" or ")' "$fixture_manifest")"

adb_cmd wait-for-device >/dev/null
reverse_fixture_ports

gradle_abi="$(detect_gradle_abi)"

if [[ "$device_profile" == "physical_indirect" ]]; then
    fail_if_existing_install_blocks_physical_runner
    install_physical_indirect_builds
    instrumentation_component="$(resolve_instrumentation_component)"
    if [[ -z "$instrumentation_component" ]]; then
        echo "Unable to resolve instrumentation component for $app_package after install." >&2
        exit 1
    fi
    require_run_as
else
    ANDROID_SERIAL="$android_serial" \
    ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest "-Pripdpi.localNativeAbis=${gradle_abi}" >/dev/null
fi

jq_selector='.[] | select(.lane == "android_proxy" or .lane == "android_vpn")'
if [[ -n "$scenario_filter" ]]; then
    jq_selector+=' | select(.id == $scenario_filter or .testSelector == $scenario_filter)'
fi

mapfile -t scenarios < <(
    jq -r --arg scenario_filter "$scenario_filter" \
        "$jq_selector | [.id, .lane, .testSelector, .trafficKind] | @tsv" \
        "$registry"
)

if [[ "${#scenarios[@]}" -eq 0 ]]; then
    echo "No Android packet smoke scenarios matched filter: ${scenario_filter:-<all>}" >&2
    exit 1
fi

for row in "${scenarios[@]}"; do
    IFS=$'\t' read -r scenario_id scenario_lane test_selector traffic_kind <<<"$row"
    if ! scenario_is_supported "$traffic_kind"; then
        echo "==> Skipping Android packet smoke: $scenario_id (requires direct UDP reachability to the host fixture)"
        continue
    fi

    scenario_dir="$artifact_root/$scenario_id"
    rm -rf "$scenario_dir"
    mkdir -p "$scenario_dir"
    cp "$fixture_manifest" "$scenario_dir/fixture-manifest.json"

    curl -fsS -X POST "http://127.0.0.1:${fixture_control_port}/events/reset" >/dev/null
    curl -fsS -X POST "http://127.0.0.1:${fixture_control_port}/faults/reset" >/dev/null
    adb_cmd logcat -c >/dev/null 2>&1 || true

    echo "==> Android packet smoke: $scenario_id"
    capture_pid="$(start_device_capture "$scenario_id" | tr -d '\r')"

    : >"$scenario_dir/test-output.txt"
    if [[ "$device_profile" == "physical_indirect" && "$scenario_lane" == "android_vpn" ]]; then
        set +e
        run_physical_indirect_vpn_scenario "$scenario_id" "$test_selector" "$traffic_kind" "$scenario_dir"
        status=$?
        set -e
    else
        set +e
        ANDROID_SERIAL="$android_serial" \
        ./gradlew :app:connectedDebugAndroidTest \
            "-Pripdpi.localNativeAbis=${gradle_abi}" \
            "-Pandroid.testInstrumentationRunnerArguments.class=${test_selector}" \
            "-Pandroid.testInstrumentationRunnerArguments.ripdpi.fixtureControlHost=${fixture_android_host}" \
            "-Pandroid.testInstrumentationRunnerArguments.ripdpi.fixtureControlPort=${fixture_control_port}" \
            "-Pandroid.testInstrumentationRunnerArguments.ripdpi.packetSmokeDeviceProfile=${device_profile}" \
            2>&1 | tee "$scenario_dir/test-output.txt"
        status=${PIPESTATUS[0]}
        set -e
        if [[ "$scenario_lane" == "android_vpn" ]]; then
            record_single_phase_vpn_artifacts "$scenario_id" "$scenario_dir"
        fi
    fi

    stop_device_capture "$scenario_id" "$scenario_dir" "$capture_pid"
    collect_device_snapshot "$scenario_dir"
    curl -fsS "http://127.0.0.1:${fixture_control_port}/events" >"$scenario_dir/fixture-events.json" || true

    if [[ "$status" -ne 0 ]]; then
        adb_cmd exec-out screencap -p >"$scenario_dir/failure-screenshot.png" 2>/dev/null || true
        exit "$status"
    fi
done
