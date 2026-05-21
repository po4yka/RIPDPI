#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
adb_bin="${ADB:-adb}"
app_id="${RIPDPI_APP_ID:-com.poyka.ripdpi}"
if [[ -f "$lab_root/artifacts/lab-env.sh" ]]; then
  # shellcheck disable=SC1091
  source "$lab_root/artifacts/lab-env.sh"
fi
profile="${RIPDPI_LAB_PROFILE:-emulator}"
mode="${RIPDPI_PROBE_MODE:-vpn}"
lab_host="${MACBOOK_LAN_IP:-}"
lab_host_from_cli=false
timeout_ms="${RIPDPI_PROBE_TIMEOUT_MS:-5000}"
dns_server=""
dns_port="${RIPDPI_DNS_PORT:-53}"
dns_hostname=""
http_url=""
https_url=""
tcp_host=""
tcp_port=""
udp_host=""
udp_port=""
quic_url=""
relay_endpoint=""
relay_auth=""
proxy_host=""
proxy_port=""
require_vpn_active=""
require_proxy_ready=""
print_broadcast=false
out_dir="$lab_root/artifacts"

usage() {
  cat <<USAGE
Usage: $0 [options]

Options:
  --profile emulator|device|physical|custom
  --mode vpn|proxy|diagnostics
  --lab-host, --host HOST
  --timeout-ms VALUE
  --dns-server HOST
  --dns-port PORT
  --dns-hostname HOSTNAME
  --http-url URL
  --https-url URL
  --tcp-host HOST
  --tcp-port PORT
  --udp-host HOST
  --udp-port PORT
  --quic-url URL
  --relay-endpoint HOST:PORT
  --relay-auth VALUE
  --proxy-host HOST
  --proxy-port PORT
  --require-vpn-active true|false
  --require-proxy-ready true|false
  --print-broadcast          Print the resolved adb broadcast and exit.
  --out-dir PATH
  -h, --help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)
      profile="${2:?missing --profile value}"
      shift 2
      ;;
    --mode)
      mode="${2:?missing --mode value}"
      shift 2
      ;;
    --lab-host|--host)
      lab_host="${2:?missing --lab-host value}"
      lab_host_from_cli=true
      shift 2
      ;;
    --timeout-ms)
      timeout_ms="${2:?missing --timeout-ms value}"
      shift 2
      ;;
    --dns-server)
      dns_server="${2:?missing --dns-server value}"
      shift 2
      ;;
    --dns-port)
      dns_port="${2:?missing --dns-port value}"
      shift 2
      ;;
    --dns-hostname)
      dns_hostname="${2:?missing --dns-hostname value}"
      shift 2
      ;;
    --http-url)
      http_url="${2:?missing --http-url value}"
      shift 2
      ;;
    --https-url)
      https_url="${2:?missing --https-url value}"
      shift 2
      ;;
    --tcp-host)
      tcp_host="${2:?missing --tcp-host value}"
      shift 2
      ;;
    --tcp-port)
      tcp_port="${2:?missing --tcp-port value}"
      shift 2
      ;;
    --udp-host)
      udp_host="${2:?missing --udp-host value}"
      shift 2
      ;;
    --udp-port)
      udp_port="${2:?missing --udp-port value}"
      shift 2
      ;;
    --quic-url)
      quic_url="${2:?missing --quic-url value}"
      shift 2
      ;;
    --relay-endpoint)
      relay_endpoint="${2:?missing --relay-endpoint value}"
      shift 2
      ;;
    --relay-auth)
      relay_auth="${2:?missing --relay-auth value}"
      shift 2
      ;;
    --proxy-host)
      proxy_host="${2:?missing --proxy-host value}"
      shift 2
      ;;
    --proxy-port)
      proxy_port="${2:?missing --proxy-port value}"
      shift 2
      ;;
    --require-vpn-active)
      require_vpn_active="${2:?missing --require-vpn-active value}"
      shift 2
      ;;
    --require-proxy-ready)
      require_proxy_ready="${2:?missing --require-proxy-ready value}"
      shift 2
      ;;
    --print-broadcast)
      print_broadcast=true
      shift
      ;;
    --out-dir)
      out_dir="${2:?missing --out-dir value}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

detect_current_lab_host() {
  MACBOOK_LAN_IP= "$lab_root/scripts/print-host-env.sh" | awk -F= '/^MACBOOK_LAN_IP=/{print $2}'
}

host_ip_is_assigned() {
  local candidate="$1"
  [[ -n "$candidate" ]] || return 1

  if command -v ifconfig >/dev/null 2>&1; then
    if ifconfig 2>/dev/null | awk '/^[[:space:]]*inet / {print $2}' | grep -Fxq "$candidate"; then
      return 0
    fi
  fi

  if command -v hostname >/dev/null 2>&1; then
    if hostname -I 2>/dev/null | tr ' ' '\n' | grep -Fxq "$candidate"; then
      return 0
    fi
  fi

  return 1
}

if [[ "$profile" == "device" || "$profile" == "physical" ]]; then
  if [[ "$lab_host_from_cli" != "true" ]]; then
    current_lab_host="$(detect_current_lab_host)"
    if [[ -z "$lab_host" ]]; then
      lab_host="$current_lab_host"
    elif [[ -n "$current_lab_host" && "$lab_host" != "$current_lab_host" ]] && ! host_ip_is_assigned "$lab_host"; then
      echo "Ignoring stale lab host from environment/artifacts: $lab_host; using current host IP: $current_lab_host" >&2
      lab_host="$current_lab_host"
    fi
  fi
fi

broadcast=(
  "$adb_bin" shell am broadcast
  -a com.poyka.ripdpi.DEBUG_PROBE
  -n "$app_id/.debug.DebugNetworkProbeReceiver"
  --es profile "$profile"
  --es mode "$mode"
  --el timeout_ms "$timeout_ms"
  --ei dns_port "$dns_port"
)

if [[ -n "$lab_host" ]]; then
  broadcast+=(--es lab_host "$lab_host")
fi
if [[ -n "$dns_server" ]]; then
  broadcast+=(--es dns_server "$dns_server")
fi
if [[ -n "$dns_hostname" ]]; then
  broadcast+=(--es dns_hostname "$dns_hostname")
fi
if [[ -n "$http_url" ]]; then
  broadcast+=(--es http_url "$http_url")
fi
if [[ -n "$https_url" ]]; then
  broadcast+=(--es https_url "$https_url")
fi
if [[ -n "$tcp_host" ]]; then
  broadcast+=(--es tcp_host "$tcp_host")
fi
if [[ -n "$tcp_port" ]]; then
  broadcast+=(--ei tcp_port "$tcp_port")
fi
if [[ -n "$udp_host" ]]; then
  broadcast+=(--es udp_host "$udp_host")
fi
if [[ -n "$udp_port" ]]; then
  broadcast+=(--ei udp_port "$udp_port")
fi
if [[ -n "$quic_url" ]]; then
  broadcast+=(--es quic_url "$quic_url")
fi
if [[ -n "$relay_endpoint" ]]; then
  broadcast+=(--es relay_endpoint "$relay_endpoint")
fi
if [[ -n "$relay_auth" ]]; then
  broadcast+=(--es relay_auth "$relay_auth")
fi
if [[ -n "$proxy_host" ]]; then
  broadcast+=(--es proxy_host "$proxy_host")
fi
if [[ -n "$proxy_port" ]]; then
  broadcast+=(--ei proxy_port "$proxy_port")
fi
if [[ -n "$require_vpn_active" ]]; then
  broadcast+=(--ez require_vpn_active "$require_vpn_active")
fi
if [[ -n "$require_proxy_ready" ]]; then
  broadcast+=(--ez require_proxy_ready "$require_proxy_ready")
fi

if [[ "$print_broadcast" == "true" ]]; then
  printf '%q ' "${broadcast[@]}"
  printf '\n'
  exit 0
fi

mkdir -p "$out_dir"
remote_output="/sdcard/Android/data/$app_id/files/probe-result.json"
local_output="$out_dir/probe-${profile}-${mode}.json"
"$adb_bin" shell "rm -f '$remote_output'" >/dev/null 2>&1 || true

"${broadcast[@]}" >/dev/null

for _ in {1..30}; do
  if "$adb_bin" shell "test -f '$remote_output'" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

"$adb_bin" pull "$remote_output" "$local_output" >/dev/null
jq -e '.runId and .profile and .mode and .verdict and .dns and .http and .tcp' "$local_output" >/dev/null

verdict="$(jq -r '.verdict' "$local_output")"
cat "$local_output"
echo

if [[ "$verdict" == "Fail" ]]; then
  exit 1
fi
