#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
registry="$repo_root/scripts/ci/packet-smoke-scenarios.json"
start_fixture_script="$repo_root/scripts/ci/start-local-network-fixture.sh"
stop_fixture_script="$repo_root/scripts/ci/stop-local-network-fixture.sh"

artifact_root="${RIPDPI_PACKET_SMOKE_ARTIFACT_DIR:-$repo_root/build/packet-smoke/android}"
scenario_filter="${RIPDPI_PACKET_SMOKE_SCENARIO_FILTER:-}"
capture_mode="${RIPDPI_PACKET_SMOKE_CAPTURE_MODE:-auto}"
fixture_android_host_override="${RIPDPI_FIXTURE_ANDROID_HOST:-}"
gradle_abi_override="${RIPDPI_LOCAL_NATIVE_ABI:-}"
android_serial="${ANDROID_SERIAL:-}"

fixture_pid_file=""
fixture_manifest=""
fixture_control_port=""
fixture_android_host=""
selected_capture_mode=""
device_capture_filter=""
is_emulator="0"

adb_cmd() {
    if [[ -n "$android_serial" ]]; then
        adb -s "$android_serial" "$@"
    else
        adb "$@"
    fi
}

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "Missing required command: $1" >&2
        exit 1
    fi
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

scenario_is_supported() {
    local traffic_kind="$1"
    if [[ "$fixture_android_host" == "127.0.0.1" && "$is_emulator" != "1" ]]; then
        case "$traffic_kind" in
            vpn_dns_doq|vpn_dns_doq_fault)
                return 1
                ;;
        esac
    fi
    return 0
}

require_command adb
require_command curl
require_command jq

mkdir -p "$artifact_root"
detect_emulator
fixture_android_host="$(resolve_fixture_android_host)"
detect_capture_mode

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

ANDROID_SERIAL="$android_serial" \
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest "-Pripdpi.localNativeAbis=${gradle_abi}" >/dev/null

jq_selector='.[] | select(.lane == "android_proxy" or .lane == "android_vpn")'
if [[ -n "$scenario_filter" ]]; then
    jq_selector+=' | select(.id == $scenario_filter or .testSelector == $scenario_filter)'
fi

mapfile -t scenarios < <(
    jq -r --arg scenario_filter "$scenario_filter" \
        "$jq_selector | [.id, .testSelector, .trafficKind] | @tsv" \
        "$registry"
)

if [[ "${#scenarios[@]}" -eq 0 ]]; then
    echo "No Android packet smoke scenarios matched filter: ${scenario_filter:-<all>}" >&2
    exit 1
fi

for row in "${scenarios[@]}"; do
    IFS=$'\t' read -r scenario_id test_selector traffic_kind <<<"$row"
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

    set +e
    ANDROID_SERIAL="$android_serial" \
    ./gradlew :app:connectedDebugAndroidTest \
        "-Pripdpi.localNativeAbis=${gradle_abi}" \
        "-Pandroid.testInstrumentationRunnerArguments.class=${test_selector}" \
        "-Pandroid.testInstrumentationRunnerArguments.ripdpi.fixtureControlHost=${fixture_android_host}" \
        "-Pandroid.testInstrumentationRunnerArguments.ripdpi.fixtureControlPort=${fixture_control_port}" \
        2>&1 | tee "$scenario_dir/test-output.txt"
    status=${PIPESTATUS[0]}
    set -e

    stop_device_capture "$scenario_id" "$scenario_dir" "$capture_pid"
    collect_device_snapshot "$scenario_dir"
    curl -fsS "http://127.0.0.1:${fixture_control_port}/events" >"$scenario_dir/fixture-events.json" || true

    if [[ "$status" -ne 0 ]]; then
        adb_cmd exec-out screencap -p >"$scenario_dir/failure-screenshot.png" 2>/dev/null || true
        exit "$status"
    fi
done
