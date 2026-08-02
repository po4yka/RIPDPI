#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

usage() {
    echo "Usage: $0 --device SERIAL --fixture-ssh USER@HOST --fixture-ssh-port PORT --fixture-ipv4 IP --fixture-ipv6 IP --output-dir ABSOLUTE_DIR [--app-apk ABSOLUTE_APK --test-apk ABSOLUTE_APK] [--fixture-tailscale-proxy --fixture-host-key-alias HOST]" >&2
}

device=""
fixture_ssh=""
fixture_ssh_port=""
fixture_ipv4=""
fixture_ipv6=""
output_dir=""
fixture_tailscale_proxy=0
fixture_host_key_alias=""
app_apk=""
test_apk=""
while (($#)); do
    case "$1" in
        --device) device="$2"; shift 2 ;;
        --fixture-ssh) fixture_ssh="$2"; shift 2 ;;
        --fixture-ssh-port) fixture_ssh_port="$2"; shift 2 ;;
        --fixture-ipv4) fixture_ipv4="$2"; shift 2 ;;
        --fixture-ipv6) fixture_ipv6="$2"; shift 2 ;;
        --output-dir) output_dir="$2"; shift 2 ;;
        --app-apk) app_apk="$2"; shift 2 ;;
        --test-apk) test_apk="$2"; shift 2 ;;
        --fixture-tailscale-proxy) fixture_tailscale_proxy=1; shift ;;
        --fixture-host-key-alias) fixture_host_key_alias="$2"; shift 2 ;;
        *) usage; exit 2 ;;
    esac
done
if [[ -z "$device" || -z "$fixture_ssh" || -z "$fixture_ssh_port" || -z "$fixture_ipv4" || -z "$fixture_ipv6" || "$output_dir" != /* ]]; then
    usage
    exit 2
fi
if [[ -n "$app_apk" || -n "$test_apk" ]]; then
    if [[ "$app_apk" != /* || "$test_apk" != /* || ! -f "$app_apk" || ! -f "$test_apk" ]]; then
        echo "Prebuilt evidence APKs must be supplied together as absolute regular files." >&2
        exit 2
    fi
    app_apk="$(realpath "$app_apk")"
    test_apk="$(realpath "$test_apk")"
fi
if ((fixture_tailscale_proxy)); then
    if [[ ! "$fixture_host_key_alias" =~ ^[A-Za-z0-9.:-]+$ ]]; then
        echo "Tailscale proxy mode requires a safe pinned host-key alias." >&2
        exit 2
    fi
elif [[ -n "$fixture_host_key_alias" ]]; then
    echo "A host-key alias is only valid with Tailscale proxy mode." >&2
    exit 2
fi

cd "$repo_root"
source_sha="$(git rev-parse HEAD)"
if [[ ! "$source_sha" =~ ^[0-9a-f]{40}$ ]] || [[ -n "$(git status --porcelain --untracked-files=all)" ]]; then
    echo "Physical evidence requires an exact clean checkout." >&2
    exit 2
fi
if [[ -e "$output_dir" ]]; then
    echo "Output directory must not already exist: $output_dir" >&2
    exit 2
fi
case "$output_dir/" in
    "$repo_root"/*) echo "Output directory must stay outside the source checkout." >&2; exit 2 ;;
esac

adb=(adb -s "$device")
ssh_options=(-o BatchMode=yes -o ConnectTimeout=10)
if ((fixture_tailscale_proxy)); then
    tailscale_path="$(command -v tailscale || true)"
    if [[ -z "$tailscale_path" ]]; then
        echo "Tailscale proxy mode requires the tailscale CLI." >&2
        exit 2
    fi
    ssh_options+=(
        -o "ProxyCommand=$tailscale_path nc %h %p"
        -o "HostKeyAlias=$fixture_host_key_alias"
        -o StrictHostKeyChecking=yes
    )
fi
ssh_base=(ssh -p "$fixture_ssh_port" "${ssh_options[@]}")
ssh_remote=("${ssh_base[@]}" "$fixture_ssh")
scp_remote=(scp -P "$fixture_ssh_port" "${ssh_options[@]}")
marker_port=39001
marker_relay_port=39005
dns_port=39003
tcp_echo_port=46001
socks_port=46080
control_port=46090
run_id="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
run_short="${run_id:0:16}"
remote_dir="/tmp/ripdpi-ordinary-$run_short"
marker_ssh_control="/tmp/ripdpi-ordinary-marker-$run_short.sock"
fixture_unit="ripdpi-ordinary-fixture-$run_short"
dns_unit="ripdpi-ordinary-dns-$run_short"
capture_unit="ripdpi-ordinary-capture-$run_short"
nft_comment="ripdpi-ordinary-$run_short"
remote_started=0
marker_relay_pid=0
marker_ssh_master_pid=0
marker_reverse_started=0
target_preinstalled=0
test_preinstalled=0
wifi_before=""
always_on_before=""
lockdown_before=""
vpn_manager_rebooted=0

wait_for_device_boot() {
    local attempt
    "${adb[@]}" wait-for-device
    for ((attempt = 0; attempt < 90; attempt++)); do
        if [[ "$("${adb[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
            return 0
        fi
        sleep 2
    done
    echo "Physical device did not finish booting within 180 seconds." >&2
    return 1
}

wait_for_user_unlock() {
    local elapsed=0
    local timeout_seconds=86400
    if "${adb[@]}" shell dumpsys user | grep -q 'State: RUNNING_UNLOCKED'; then
        return
    fi
    echo "Unlock the physical Android device to continue release evidence collection." >&2
    while ((elapsed < timeout_seconds)); do
        sleep 2
        elapsed=$((elapsed + 2))
        if "${adb[@]}" shell dumpsys user | grep -q 'State: RUNNING_UNLOCKED'; then
            return
        fi
    done
    echo "The physical Android device was not unlocked within ${timeout_seconds}s." >&2
    return 1
}

configure_vpn_manager_profile() {
    local package_name="$1"
    local enabled="$2"
    local lockdown="$3"
    local transcript_path="$4"
    local instrumentation_status

    if [[ ! "$package_name" =~ ^[A-Za-z0-9._]+$ ]] || [[ ! "$enabled" =~ ^(true|false)$ ]] || [[ ! "$lockdown" =~ ^(true|false)$ ]]; then
        echo "Invalid VPN manager configurator arguments." >&2
        return 2
    fi

    set +e
    "${adb[@]}" shell am instrument -w -r \
        -e class com.poyka.ripdpi.e2e.AlwaysOnVpnSettingsConfiguratorTest#configureAlwaysOnVpnThroughSettings \
        -e ripdpi.alwaysOnPackage "$package_name" \
        -e ripdpi.alwaysOnEnabled "$enabled" \
        -e ripdpi.alwaysOnLockdown "$lockdown" \
        com.poyka.ripdpi.test/com.poyka.ripdpi.HiltTestRunner >"$transcript_path" 2>&1
    instrumentation_status=$?
    set -e
    chmod 0600 "$transcript_path"
    if ((instrumentation_status != 0)) || [[ "$(grep -c '^OK (1 test)$' "$transcript_path")" != "1" ]] || grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|AssumptionViolated' "$transcript_path"; then
        echo "Android VPN manager configurator did not finish as OK (1 test)." >&2
        return 1
    fi
}

cleanup_remote() {
    if ((remote_started)); then
        "${ssh_remote[@]}" "set +e; sudo systemctl stop '$capture_unit.service' '$dns_unit.service' '$fixture_unit.service' >/dev/null 2>&1; for handle in \$(sudo nft -a list chain inet filter input | awk -v marker='$nft_comment' 'index(\$0, marker) {print \$NF}'); do sudo nft delete rule inet filter input handle \"\$handle\"; done; sudo rm -rf -- '$remote_dir'" >/dev/null 2>&1 || true
    fi
}

collect_failure_diagnostics() {
    local failed_capture="$output_dir/failed-fixture-observer.pcap"
    local failed_journal="$output_dir/failed-fixture-journal.txt"
    if ((remote_started)); then
        "${ssh_remote[@]}" "set +e; sudo systemctl stop '$capture_unit.service'; sudo journalctl --no-pager --output=short-monotonic -u '$dns_unit.service' -u '$fixture_unit.service' >'$remote_dir/failure-journal.txt'; sudo chmod 0600 '$remote_dir/capture.pcap' '$remote_dir/failure-journal.txt'; sudo chown \"\$(id -u):\$(id -g)\" '$remote_dir/capture.pcap' '$remote_dir/failure-journal.txt'" >/dev/null 2>&1 || true
        "${scp_remote[@]}" "$fixture_ssh:$remote_dir/capture.pcap" "$failed_capture" >/dev/null 2>&1 || true
        "${scp_remote[@]}" "$fixture_ssh:$remote_dir/failure-journal.txt" "$failed_journal" >/dev/null 2>&1 || true
        [[ ! -f "$failed_capture" ]] || chmod 0600 "$failed_capture"
        [[ ! -f "$failed_journal" ]] || chmod 0600 "$failed_journal"
    fi
}

cleanup_marker_transport() {
    if ((marker_reverse_started)); then
        "${adb[@]}" reverse --remove "tcp:$marker_relay_port" >/dev/null 2>&1 || true
    fi
    if ((marker_relay_pid)); then
        kill "$marker_relay_pid" >/dev/null 2>&1 || true
        wait "$marker_relay_pid" >/dev/null 2>&1 || true
    fi
    if ((marker_ssh_master_pid)); then
        if [[ -S "$marker_ssh_control" ]]; then
            ssh -S "$marker_ssh_control" -O exit "$fixture_ssh" >/dev/null 2>&1 || true
        fi
        kill "$marker_ssh_master_pid" >/dev/null 2>&1 || true
        wait "$marker_ssh_master_pid" >/dev/null 2>&1 || true
    fi
}

cleanup_device() {
    if [[ "$wifi_before" == "1" ]]; then
        "${adb[@]}" shell svc wifi enable >/dev/null 2>&1 || true
    elif [[ -n "$wifi_before" ]]; then
        "${adb[@]}" shell svc wifi disable >/dev/null 2>&1 || true
    fi
    "${adb[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    "${adb[@]}" shell am force-stop com.poyka.ripdpi >/dev/null 2>&1 || true
    if [[ -n "$always_on_before" ]]; then
        if [[ "$always_on_before" == "null" ]]; then
            "${adb[@]}" shell settings delete secure always_on_vpn_app >/dev/null 2>&1 || true
        else
            "${adb[@]}" shell settings put secure always_on_vpn_app "$always_on_before" >/dev/null 2>&1 || true
        fi
    fi
    if [[ -n "$lockdown_before" ]]; then
        if [[ "$lockdown_before" == "null" ]]; then
            "${adb[@]}" shell settings delete secure always_on_vpn_lockdown >/dev/null 2>&1 || true
        else
            "${adb[@]}" shell settings put secure always_on_vpn_lockdown "$lockdown_before" >/dev/null 2>&1 || true
        fi
    fi
    "${adb[@]}" shell am force-stop com.poyka.ripdpi.test >/dev/null 2>&1 || true
    if ((test_preinstalled == 0)); then
        "${adb[@]}" uninstall com.poyka.ripdpi.test >/dev/null 2>&1 || true
    fi
    if ((target_preinstalled == 0)); then
        "${adb[@]}" uninstall com.poyka.ripdpi >/dev/null 2>&1 || true
    fi
    if ((vpn_manager_rebooted)); then
        "${adb[@]}" reboot >/dev/null 2>&1 || true
        wait_for_device_boot >/dev/null 2>&1 || true
        "${adb[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
        "${adb[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
    fi
}

cleanup() {
    cleanup_marker_transport
    cleanup_device
    cleanup_remote
}
trap cleanup EXIT INT TERM

device_state="$("${adb[@]}" get-state)"
device_product="$("${adb[@]}" shell getprop ro.build.product | tr -d '\r')"
device_qemu="$("${adb[@]}" shell getprop ro.kernel.qemu | tr -d '\r')"
device_manufacturer="$("${adb[@]}" shell getprop ro.product.manufacturer | tr -d '\r')"
device_abi="$("${adb[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')"
if [[ "$device_state" != "device" || "$device_qemu" == "1" || "$device_product" =~ ^(generic|ranchu|goldfish)$ || "${device_manufacturer,,}" != "google" || ! "$device_abi" =~ ^[A-Za-z0-9_-]+$ ]]; then
    echo "The selected serial is not an approved physical Google Android device." >&2
    exit 2
fi
wait_for_user_unlock
wifi_before="$("${adb[@]}" shell settings get global wifi_on | tr -d '\r')"
always_on_before="$("${adb[@]}" shell settings get secure always_on_vpn_app | tr -d '\r')"
lockdown_before="$("${adb[@]}" shell settings get secure always_on_vpn_lockdown | tr -d '\r')"
if "${adb[@]}" shell pm path com.poyka.ripdpi 2>/dev/null | grep -q '^package:'; then
    target_preinstalled=1
    if [[ "${RIPDPI_ORDINARY_ALLOW_REPLACE_INSTALLED:-0}" != "1" ]]; then
        echo "com.poyka.ripdpi is already installed; set RIPDPI_ORDINARY_ALLOW_REPLACE_INSTALLED=1 only after preserving its state." >&2
        exit 2
    fi
fi
if "${adb[@]}" shell pm path com.poyka.ripdpi.test 2>/dev/null | grep -q '^package:'; then
    test_preinstalled=1
fi
if [[ "$always_on_before" != "null" && ! "$always_on_before" =~ ^[A-Za-z0-9._]+$ ]]; then
    echo "The saved always-on VPN package name is invalid." >&2
    exit 2
fi
"${adb[@]}" shell settings put secure always_on_vpn_lockdown 0
"${adb[@]}" shell settings delete secure always_on_vpn_app >/dev/null
if [[ "$always_on_before" != "null" ]]; then
    "${adb[@]}" shell am force-stop "$always_on_before"
fi

mkdir -p "$output_dir"
chmod 0700 "$output_dir"
transcript="$output_dir/instrumentation.txt"
observations="$output_dir/observations.json"
capture="$output_dir/fixture-observer.pcap"
artifact_root="$output_dir/raw-artifacts"
manifest="$output_dir/raw-manifest.json"
attestation="$output_dir/physical-attestation.json"
results="$output_dir/ordinary-results.json"

if [[ -z "$app_apk" ]]; then
    ./gradlew :app:assembleGithubFullDebug :app:assembleGithubFullDebugAndroidTest
    app_apk="$repo_root/app/build/outputs/apk/githubFull/debug/app-github-full-${device_abi}-debug.apk"
    test_apk="$repo_root/app/build/outputs/apk/androidTest/githubFull/debug/app-github-full-debug-androidTest.apk"
fi
test -f "$app_apk" -a -f "$test_apk"
app_sha="$(shasum -a 256 "$app_apk" | awk '{print $1}')"
test_sha="$(shasum -a 256 "$test_apk" | awk '{print $1}')"

sysroot="$(x86_64-linux-gnu-gcc -print-sysroot)"
BINDGEN_EXTRA_CLANG_ARGS_x86_64_unknown_linux_gnu="--sysroot=$sysroot" \
    CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER=x86_64-linux-gnu-gcc \
    cargo build --locked --release --target x86_64-unknown-linux-gnu \
    --manifest-path native/rust/Cargo.toml -p local-network-fixture
fixture_binary="$(cargo metadata --locked --no-deps --format-version 1 --manifest-path native/rust/Cargo.toml | jq -r .target_directory)/x86_64-unknown-linux-gnu/release/local-network-fixture"
if file "$fixture_binary" | grep -q 'Mach-O'; then
    echo "Cross-built fixture is not an ELF binary." >&2
    exit 2
fi

"${adb[@]}" install -r -t "$app_apk" >/dev/null
"${adb[@]}" install -r -t "$test_apk" >/dev/null
installed_app_path="$("${adb[@]}" shell pm path com.poyka.ripdpi | sed -n 's/^package://p' | tr -d '\r' | head -n1)"
installed_test_path="$("${adb[@]}" shell pm path com.poyka.ripdpi.test | sed -n 's/^package://p' | tr -d '\r' | head -n1)"
installed_app_copy="$output_dir/installed-app.apk"
installed_test_copy="$output_dir/installed-test.apk"
"${adb[@]}" pull "$installed_app_path" "$installed_app_copy" >/dev/null
"${adb[@]}" pull "$installed_test_path" "$installed_test_copy" >/dev/null
installed_app_sha="$(shasum -a 256 "$installed_app_copy" | awk '{print $1}')"
installed_test_sha="$(shasum -a 256 "$installed_test_copy" | awk '{print $1}')"
rm -f "$installed_app_copy" "$installed_test_copy"
if [[ "$installed_app_sha" != "$app_sha" || "$installed_test_sha" != "$test_sha" ]]; then
    echo "Installed APK readback differs from the exact build outputs." >&2
    exit 2
fi
"${adb[@]}" shell cmd appops set com.poyka.ripdpi ACTIVATE_VPN allow
configure_vpn_manager_profile com.poyka.ripdpi true true "$output_dir/always-on-setup.txt"
vpn_manager_rebooted=1
"${adb[@]}" reboot
wait_for_device_boot
"${adb[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null
"${adb[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
wait_for_user_unlock
"${adb[@]}" shell am force-stop com.poyka.ripdpi
test "$("${adb[@]}" shell settings get secure always_on_vpn_app | tr -d '\r')" = "com.poyka.ripdpi"
test "$("${adb[@]}" shell settings get secure always_on_vpn_lockdown | tr -d '\r')" = "1"

remote_started=1
"${ssh_remote[@]}" "set -e; test ! -e '$remote_dir'; install -d -m 0700 '$remote_dir'; test \"\$(id -u)\" != 0; sudo -n true; command -v tcpdump >/dev/null; command -v python3 >/dev/null; command -v systemd-run >/dev/null; command -v nft >/dev/null; sudo nft list chain inet filter input >/dev/null"
"${scp_remote[@]}" "$fixture_binary" "$repo_root/scripts/ci/android_ordinary_dns_fixture.py" "$fixture_ssh:$remote_dir/" >/dev/null
"${ssh_remote[@]}" "set -e; chmod 0500 '$remote_dir/local-network-fixture' '$remote_dir/android_ordinary_dns_fixture.py'; sudo nft insert rule inet filter input tcp dport { $dns_port, $tcp_echo_port, $socks_port, $control_port } accept comment '$nft_comment'; sudo nft insert rule inet filter input udp dport { $marker_port, $dns_port, $socks_port } accept comment '$nft_comment'; sudo systemd-run --quiet --unit='$fixture_unit' --property=RuntimeMaxSec=1800 --setenv=RIPDPI_FIXTURE_BIND_HOST=:: --setenv=RIPDPI_FIXTURE_ANDROID_HOST='$fixture_ipv4' '$remote_dir/local-network-fixture'; sudo systemd-run --quiet --unit='$dns_unit' --property=RuntimeMaxSec=1800 '$remote_dir/android_ordinary_dns_fixture.py' --port '$dns_port' --dual-stack-ipv6 '$fixture_ipv6'; sudo systemd-run --quiet --unit='$capture_unit' --property=RuntimeMaxSec=1800 /usr/bin/tcpdump -i any -U -n -s 0 -w '$remote_dir/capture.pcap' \"(udp dst port $marker_port) or (udp port $dns_port) or (tcp port $dns_port) or (tcp port $socks_port) or (tcp port $tcp_echo_port)\"; sleep 2; systemctl is-active --quiet '$fixture_unit.service'; systemctl is-active --quiet '$dns_unit.service'; systemctl is-active --quiet '$capture_unit.service'"

"${ssh_remote[@]}" "python3 -c 'import socket; sock = socket.create_connection((\"::1\", $control_port), timeout=5); sock.close()'"
"${ssh_remote[@]}" "python3 -c 'import socket; sock = socket.create_connection((\"::1\", $tcp_echo_port), timeout=5); sock.close()'"
"${ssh_base[@]}" -M -S "$marker_ssh_control" -N "$fixture_ssh" &
marker_ssh_master_pid=$!
for _ in {1..40}; do
    if ssh -S "$marker_ssh_control" -O check "$fixture_ssh" >/dev/null 2>&1; then
        break
    fi
    if ! kill -0 "$marker_ssh_master_pid" 2>/dev/null; then
        echo "Fixture marker SSH control connection exited before becoming ready." >&2
        exit 1
    fi
    sleep 0.25
done
ssh -S "$marker_ssh_control" -O check "$fixture_ssh" >/dev/null
remote_marker_sender="python3 -c 'import socket,sys; payload=sys.stdin.buffer.read(161); sock=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); sent=sock.sendto(payload,(sys.argv[1],int(sys.argv[2]))); sys.exit(sent != len(payload))' '$fixture_ipv4' '$marker_port'"
marker_forward_command=(
    ssh -S "$marker_ssh_control" -o ControlMaster=no "$fixture_ssh"
    "$remote_marker_sender"
)
python3 "$repo_root/scripts/ci/android_ordinary_marker_relay.py" \
    --listen-port "$marker_relay_port" \
    --forward-command "${marker_forward_command[@]}" &
marker_relay_pid=$!
for _ in {1..20}; do
    if python3 -c "import socket; socket.create_connection(('127.0.0.1', $marker_relay_port), timeout=1).close()" 2>/dev/null; then
        break
    fi
    if ! kill -0 "$marker_relay_pid" 2>/dev/null; then
        echo "Local fixture marker relay exited before becoming ready." >&2
        exit 1
    fi
    sleep 0.25
done
python3 -c "import socket; socket.create_connection(('127.0.0.1', $marker_relay_port), timeout=1).close()"
"${adb[@]}" reverse "tcp:$marker_relay_port" "tcp:$marker_relay_port" >/dev/null
marker_reverse_started=1

set +e
"${adb[@]}" shell am instrument -w -r \
    -e class com.poyka.ripdpi.e2e.AndroidOrdinaryPhysicalEvidenceTest#produceSevenOrdinaryPhysicalActions \
    -e ripdpi.fixtureControlHost "$fixture_ipv4" \
    -e ripdpi.fixtureControlPort "$control_port" \
    -e ripdpi.ordinarySourceSha "$source_sha" \
    -e ripdpi.ordinaryAppApkSha256 "$app_sha" \
    -e ripdpi.ordinaryTestApkSha256 "$test_sha" \
    -e ripdpi.ordinaryRunId "$run_id" \
    -e ripdpi.ordinaryControlIpv4 "$fixture_ipv4" \
    -e ripdpi.ordinaryControlIpv6 "$fixture_ipv6" \
    -e ripdpi.ordinaryMarkerPort "$marker_port" \
    -e ripdpi.ordinaryDnsPort "$dns_port" \
    -e ripdpi.ordinaryMarkerRelayPort "$marker_relay_port" \
    -e ripdpi.ordinaryTcpEchoPort "$tcp_echo_port" \
    -e ripdpi.ordinarySocksPort "$socks_port" \
    com.poyka.ripdpi.test/com.poyka.ripdpi.HiltTestRunner >"$transcript" 2>&1
instrumentation_status=$?
set -e
chmod 0600 "$transcript"
if ((instrumentation_status != 0)) || [[ "$(grep -c '^OK (1 test)$' "$transcript")" != "1" ]] || grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|AssumptionViolated' "$transcript"; then
    collect_failure_diagnostics
    echo "Exact physical instrumentation did not finish as OK (1 test)." >&2
    exit 1
fi
python3 scripts/ci/extract_android_ordinary_observations.py \
    --transcript "$transcript" \
    --output "$observations"

"${ssh_remote[@]}" "set -e; sudo systemctl stop '$capture_unit.service'; sudo chmod 0600 '$remote_dir/capture.pcap'; sudo chown \"\$(id -u):\$(id -g)\" '$remote_dir/capture.pcap'; test -s '$remote_dir/capture.pcap'"
"${scp_remote[@]}" "$fixture_ssh:$remote_dir/capture.pcap" "$capture" >/dev/null
chmod 0600 "$capture"

python3 scripts/ci/produce_android_ordinary_physical_evidence.py \
    --observations "$observations" \
    --capture "$capture" \
    --instrumentation-transcript "$transcript" \
    --app-apk "$app_apk" \
    --test-apk "$test_apk" \
    --source-sha "$source_sha" \
    --device-serial "$device" \
    --artifact-root "$artifact_root" \
    --manifest "$manifest" \
    --attestation "$attestation"
python3 scripts/ci/produce_android_ordinary_gate_results.py \
    --raw-manifest "$manifest" \
    --app-apk "$app_apk" \
    --test-apk "$test_apk" \
    --physical-attestation "$attestation" \
    --output "$results"
echo "Android ordinary physical evidence: $results"
