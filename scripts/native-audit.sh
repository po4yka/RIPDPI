#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: ./scripts/native-audit.sh <host|android|soak|full> [audit_dir]

Modes:
  host     Run deterministic native host checks and serialized fixed-port E2E suites.
  android  Run emulator-backed Android connected tests against the local network fixture.
  soak     Run ignored native soak suites with runtime metrics collection.
  full     Run host + packaging + android + live-network smoke + soak.
EOF
}

if [[ $# -lt 1 ]]; then
    usage
    exit 64
fi

mode="$1"
case "$mode" in
    host|android|soak|full) ;;
    *)
        usage
        exit 64
        ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/.. && pwd)"
native_root="$repo_root/native/rust"
workspace_manifest="$native_root/Cargo.toml"
timestamp="$(date +%Y%m%d-%H%M%S)"
audit_root="${2:-$repo_root/build/native-audit/${timestamp}-${mode}}"
logs_dir="$audit_root/logs"
fixture_dir="$audit_root/android-fixture"
live_dir="$audit_root/live-network"
report_file="$audit_root/report.txt"
metadata_file="$audit_root/metadata.txt"
coverage_file="$audit_root/coverage-matrix.md"

mkdir -p "$logs_dir" "$fixture_dir" "$live_dir"

command_counter=0
fixture_pid_file=""
emulator_pid=""
android_serial=""
android_native_abi="${RIPDPI_ANDROID_NATIVE_ABI:-}"
android_avd_name="${RIPDPI_ANDROID_AVD:-Pixel_9_Pro_XL}"
android_fixture_host="${RIPDPI_FIXTURE_CONTROL_HOST:-10.0.2.2}"
android_fixture_port="${RIPDPI_FIXTURE_CONTROL_PORT:-46090}"
declare -a android_gradle_args=()

if [[ -n "${RIPDPI_ANDROID_GRADLE_ARGS:-}" ]]; then
    read -r -a android_gradle_args <<<"$RIPDPI_ANDROID_GRADLE_ARGS"
fi

if [[ -n "${RIPDPI_ANDROID_TEST_CLASSES:-}" ]]; then
    mapfile -t android_test_classes < <(printf '%s' "$RIPDPI_ANDROID_TEST_CLASSES" | tr ',' '\n' | sed '/^[[:space:]]*$/d')
else
    android_test_classes=(
        "com.poyka.ripdpi.integration.NativeBridgeInstrumentedTest"
        "com.poyka.ripdpi.e2e.NativeTelemetryGoldenSmokeTest"
        "com.poyka.ripdpi.e2e.NetworkPathE2ETest"
        "com.poyka.ripdpi.e2e.DiagnosticsNetworkE2ETest"
        "com.poyka.ripdpi.integration.ServiceLifecycleIntegrationTest"
    )
fi

log() {
    local message="$*"
    printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$message" | tee -a "$report_file"
}

section() {
    log ""
    log "==> $*"
}

slugify() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9#' '-'
}

resolve_sdk_dir() {
    if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT:-}" ]]; then
        printf '%s\n' "$ANDROID_SDK_ROOT"
        return
    fi
    if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME:-}" ]]; then
        printf '%s\n' "$ANDROID_HOME"
        return
    fi
    if [[ -f "$repo_root/local.properties" ]]; then
        local raw
        raw="$(grep '^sdk.dir=' "$repo_root/local.properties" | cut -d= -f2- || true)"
        raw="${raw//\\:/:}"
        raw="${raw//\\\\/\\}"
        if [[ -n "$raw" && -d "$raw" ]]; then
            printf '%s\n' "$raw"
            return
        fi
    fi
    return 1
}

sdk_dir="$(resolve_sdk_dir || true)"
adb_bin="${sdk_dir:+$sdk_dir/platform-tools/adb}"
emulator_bin="${sdk_dir:+$sdk_dir/emulator/emulator}"
gradlew="$repo_root/gradlew"

write_metadata() {
    {
        printf 'mode=%s\n' "$mode"
        printf 'timestamp=%s\n' "$timestamp"
        printf 'repo_root=%s\n' "$repo_root"
        printf 'native_root=%s\n' "$native_root"
        printf 'java_version=%s\n' "$(java -version 2>&1 | head -n 1)"
        printf 'rustc_version=%s\n' "$(rustc --version 2>/dev/null || true)"
        printf 'cargo_version=%s\n' "$(cargo --version 2>/dev/null || true)"
        printf 'sdk_dir=%s\n' "$sdk_dir"
    } >"$metadata_file"

    local java_line
    java_line="$(java -version 2>&1 | head -n 1)"
    if [[ "$java_line" != *'"17.'* && "$java_line" != *' 17.'* ]]; then
        log "Toolchain drift: expected JDK 17, found ${java_line}"
    fi
}

write_coverage_matrix() {
    cat >"$coverage_file" <<'EOF'
# Native Audit Coverage

This audit uses pairwise-plus-boundary coverage rather than a literal full Cartesian settings sweep.

## Proxy Config Families
- listen
- protocols
- TCP chains and UDP chains
- fake-packet profiles
- parser evasions
- QUIC
- hosts and host autolearn
- WS tunnel

## Tunnel Config Families
- SOCKS5
- TUN basics
- mapdns
- encrypted DNS
- fallback toggles
- timeouts, buffers, and session limits

## Strategy Presets
- neutral/no-desync baseline
- ripdpi_default
- russia_rostelecom
- russia_mgts
- russia_mts_mobile

## Protocol Families
- HTTP
- HTTPS/TLS
- UDP
- QUIC v1
- QUIC v2
- plain DNS
- encrypted DNS DoH/DoT/DNSCrypt
- WS tunnel

Invalid and boundary payloads remain covered by host config/unit tests rather than emulator E2E.
EOF
    log "Coverage matrix written to $coverage_file"
}

run_step() {
    local label="$1"
    shift
    command_counter=$((command_counter + 1))
    local log_path="$logs_dir/$(printf '%02d' "$command_counter")-$(slugify "$label").log"

    section "$label"
    log "Command: $*"
    (
        cd "$repo_root"
        "$@"
    ) > >(tee "$log_path") 2> >(tee -a "$log_path" >&2)
    log "Completed: $label"
}

metric_value() {
    local pid="$1"
    local rss threads fds
    rss="$(ps -o rss= -p "$pid" 2>/dev/null | tr -d ' ' || printf '0')"
    threads="$(ps -M -p "$pid" 2>/dev/null | awk 'NR > 1 { count += 1 } END { print count + 0 }')"
    fds="$(lsof -p "$pid" 2>/dev/null | awk 'NR > 1 { count += 1 } END { print count + 0 }')"
    printf '%s,%s,%s\n' "${rss:-0}" "${threads:-0}" "${fds:-0}"
}

run_step_with_metrics() {
    local label="$1"
    shift
    command_counter=$((command_counter + 1))
    local slug log_path metrics_path
    slug="$(printf '%02d' "$command_counter")-$(slugify "$label")"
    log_path="$logs_dir/$slug.log"
    metrics_path="$logs_dir/$slug.metrics.txt"

    section "$label"
    log "Command: $*"

    (
        cd "$repo_root"
        "$@"
    ) > >(tee "$log_path") 2> >(tee -a "$log_path" >&2) &
    local pid=$!
    local start end peak duration
    start="$(metric_value "$pid")"
    peak="$start"
    local started_at
    started_at="$(date +%s)"

    while kill -0 "$pid" 2>/dev/null; do
        local current
        current="$(metric_value "$pid")"
        IFS=, read -r cur_rss cur_threads cur_fds <<<"$current"
        IFS=, read -r peak_rss peak_threads peak_fds <<<"$peak"
        [[ "${cur_rss:-0}" -gt "${peak_rss:-0}" ]] && peak_rss="$cur_rss"
        [[ "${cur_threads:-0}" -gt "${peak_threads:-0}" ]] && peak_threads="$cur_threads"
        [[ "${cur_fds:-0}" -gt "${peak_fds:-0}" ]] && peak_fds="$cur_fds"
        peak="$peak_rss,$peak_threads,$peak_fds"
        sleep 5
    done

    wait "$pid"
    end="$(metric_value "$pid")"
    duration="$(( $(date +%s) - started_at ))"
    {
        printf 'duration_seconds=%s\n' "$duration"
        printf 'start=%s\n' "$start"
        printf 'end=%s\n' "$end"
        printf 'peak=%s\n' "$peak"
    } | tee "$metrics_path" >>"$report_file"
    log "Completed with metrics: $label"
}

cleanup() {
    if [[ -n "$fixture_pid_file" ]]; then
        "$repo_root/scripts/ci/stop-local-network-fixture.sh" "$fixture_pid_file" >/dev/null 2>&1 || true
    fi

    if [[ -n "$android_serial" && -n "$adb_bin" && -x "$adb_bin" ]]; then
        "$adb_bin" -s "$android_serial" emu kill >/dev/null 2>&1 || true
    fi

    if [[ -n "$emulator_pid" ]]; then
        kill "$emulator_pid" >/dev/null 2>&1 || true
        wait "$emulator_pid" >/dev/null 2>&1 || true
    fi
}

trap cleanup EXIT

start_fixture() {
    fixture_pid_file="$fixture_dir/fixture.pid"
    run_step \
        "start local network fixture" \
        "$repo_root/scripts/ci/start-local-network-fixture.sh" \
        "$fixture_dir/fixture.log" \
        "$fixture_dir/fixture-manifest.json" \
        "$fixture_pid_file"

    log "Fixture manifest:"
    cat "$fixture_dir/fixture-manifest.json" | tee -a "$report_file"
}

fixture_manifest_port() {
    local key="$1"
    python3 - "$fixture_dir/fixture-manifest.json" "$key" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as fh:
    manifest = json.load(fh)
value = manifest.get(sys.argv[2], "")
print(value)
PY
}

wait_for_boot_complete() {
    local serial="$1"
    for _ in $(seq 1 120); do
        if "$adb_bin" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | grep -q '1'; then
            return 0
        fi
        sleep 2
    done
    return 1
}

detect_emulator_serial() {
    "$adb_bin" devices | awk 'NR > 1 && $2 == "device" && $1 ~ /^emulator-/ { print $1; exit }'
}

start_emulator() {
    if [[ -z "$sdk_dir" || -z "$adb_bin" || -z "$emulator_bin" ]]; then
        log "Android SDK/emulator tooling not available"
        return 1
    fi

    command_counter=$((command_counter + 1))
    local log_path="$logs_dir/$(printf '%02d' "$command_counter")-android-emulator.log"
    section "boot emulator $android_avd_name"
    log "Command: $emulator_bin -avd $android_avd_name -no-window -noaudio -no-boot-anim -gpu swiftshader_indirect"
    "$emulator_bin" -avd "$android_avd_name" -no-window -noaudio -no-boot-anim -gpu swiftshader_indirect \
        >"$log_path" 2>&1 &
    emulator_pid=$!

    "$adb_bin" wait-for-device >/dev/null 2>&1 || true
    for _ in $(seq 1 90); do
        android_serial="$(detect_emulator_serial || true)"
        if [[ -n "$android_serial" ]] && wait_for_boot_complete "$android_serial"; then
            log "Emulator ready: $android_serial"
            return 0
        fi
        sleep 2
    done

    log "Timed out waiting for emulator $android_avd_name"
    return 1
}

detect_android_abi() {
    if [[ -n "$android_native_abi" ]]; then
        log "Android native ABI override: $android_native_abi"
        return
    fi
    local detected_abi
    detected_abi="$("$adb_bin" -s "$android_serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
    if [[ -z "$detected_abi" ]]; then
        detected_abi="x86_64"
    fi
    android_native_abi="$detected_abi"
    if [[ "$detected_abi" == "x86_64" ]]; then
        log "Android native ABI: $android_native_abi"
    else
        log "Android runtime ABI is $android_native_abi on this Mac; using it for connected tests and leaving x86_64 verification to packaging/full checks"
    fi
}

setup_android_reverse_ports() {
    local port_key port
    for port_key in tcpEchoPort tlsEchoPort dnsHttpPort socks5Port controlPort; do
        port="$(fixture_manifest_port "$port_key")"
        if [[ -z "$port" ]]; then
            continue
        fi
        run_step "adb reverse tcp:$port tcp:$port" "$adb_bin" -s "$android_serial" reverse "tcp:$port" "tcp:$port"
    done
    run_step "adb reverse --list" "$adb_bin" -s "$android_serial" reverse --list
}

run_host_mode() {
    run_step "cargo test --workspace --all-targets --no-run" cargo test --manifest-path "$workspace_manifest" --workspace --all-targets --no-run
    run_step "cargo test -p ripdpi-proxy-config" cargo test --manifest-path "$workspace_manifest" -p ripdpi-proxy-config
    run_step "cargo test -p ripdpi-dns-resolver" cargo test --manifest-path "$workspace_manifest" -p ripdpi-dns-resolver
    run_step "cargo test -p ripdpi-monitor -- --test-threads=1" cargo test --manifest-path "$workspace_manifest" -p ripdpi-monitor -- --test-threads=1
    run_step "cargo test -p ripdpi-android" cargo test --manifest-path "$workspace_manifest" -p ripdpi-android
    run_step "cargo test -p ripdpi-tunnel-android" cargo test --manifest-path "$workspace_manifest" -p ripdpi-tunnel-android
    run_step "cargo test -p ripdpi-runtime --test network_e2e -- --test-threads=1" \
        cargo test --manifest-path "$workspace_manifest" -p ripdpi-runtime --test network_e2e -- --test-threads=1
    run_step "cargo test -p ripdpi-tunnel-core --test tun_e2e -- --test-threads=1" \
        cargo test --manifest-path "$workspace_manifest" -p ripdpi-tunnel-core --test tun_e2e -- --test-threads=1
}

run_android_mode() {
    start_fixture
    start_emulator
    detect_android_abi
    setup_android_reverse_ports
    run_step "adb logcat clear" "$adb_bin" -s "$android_serial" logcat -c

    local class_name
    for class_name in "${android_test_classes[@]}"; do
        run_step \
            "connected test $class_name" \
            env ANDROID_SERIAL="$android_serial" "$gradlew" :app:connectedDebugAndroidTest \
            "${android_gradle_args[@]}" \
            "-Pripdpi.localNativeAbis=$android_native_abi" \
            "-Pandroid.testInstrumentationRunnerArguments.class=$class_name" \
            "-Pandroid.testInstrumentationRunnerArguments.ripdpi.fixtureControlHost=$android_fixture_host" \
            "-Pandroid.testInstrumentationRunnerArguments.ripdpi.fixtureControlPort=$android_fixture_port"
    done

    run_step "capture android logcat" "$adb_bin" -s "$android_serial" logcat -d
    log "Audit artifacts: $audit_root"
}

run_soak_mode() {
    run_step_with_metrics \
        "cargo test -p ripdpi-runtime --test network_soak -- --ignored --test-threads=1" \
        cargo test --manifest-path "$workspace_manifest" -p ripdpi-runtime --test network_soak -- --ignored --test-threads=1
    run_step_with_metrics \
        "cargo test -p ripdpi-monitor --test soak -- --ignored --test-threads=1" \
        cargo test --manifest-path "$workspace_manifest" -p ripdpi-monitor --test soak -- --ignored --test-threads=1
}

run_live_network_smokes() {
    run_step "live https reachability smoke" curl -fsS --max-time 15 https://example.com/ -o "$live_dir/https-example.html"
    run_step \
        "live DoH smoke" \
        curl -fsS --max-time 15 \
        -H 'accept: application/dns-json' \
        'https://cloudflare-dns.com/dns-query?name=example.com&type=A' \
        -o "$live_dir/doh-example.json"

    if [[ -n "${RIPDPI_WS_TUNNEL_SMOKE_URL:-}" ]]; then
        run_step \
            "live ws tunnel smoke" \
            curl -fsS --max-time 20 "$RIPDPI_WS_TUNNEL_SMOKE_URL" -o "$live_dir/ws-tunnel.txt"
    else
        log "Skipping WS tunnel live smoke: set RIPDPI_WS_TUNNEL_SMOKE_URL to enable it"
    fi
}

run_full_mode() {
    run_host_mode
    run_step \
        "gradlew :core:engine:buildRustNativeLibs -Pripdpi.localNativeAbis=armeabi-v7a,arm64-v8a,x86,x86_64" \
        "$gradlew" :core:engine:buildRustNativeLibs -Pripdpi.localNativeAbis=armeabi-v7a,arm64-v8a,x86,x86_64
    run_step "gradlew :core:engine:testDebugUnitTest" "$gradlew" :core:engine:testDebugUnitTest
    run_step "gradlew :app:assembleDebug -Pripdpi.localNativeAbis=x86_64" "$gradlew" :app:assembleDebug -Pripdpi.localNativeAbis=x86_64
    run_android_mode
    run_live_network_smokes
    run_soak_mode
}

write_metadata
write_coverage_matrix

case "$mode" in
    host)
        run_host_mode
        ;;
    android)
        run_android_mode
        ;;
    soak)
        run_soak_mode
        ;;
    full)
        run_full_mode
        ;;
esac
