#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
artifact_path="$lab_root/artifacts/doctor.json"
dns_port="${RIPDPI_DNS_PORT:-1053}"

declare -a result_names=()
declare -a result_statuses=()
declare -a result_required=()
declare -a result_messages=()

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/\\r}"
  value="${value//$'\t'/\\t}"
  printf '%s' "$value"
}

add_result() {
  result_names+=("$1")
  result_statuses+=("$2")
  result_required+=("$3")
  result_messages+=("$4")
}

run_check() {
  local name="$1"
  local required="$2"
  shift 2

  local output status
  set +e
  output="$("$@" 2>&1)"
  status=$?
  set -e

  case "$status" in
    0)
      add_result "$name" "pass" "$required" "${output:-ok}"
      ;;
    10)
      add_result "$name" "skip" "$required" "${output:-skipped}"
      ;;
    *)
      add_result "$name" "fail" "$required" "${output:-failed}"
      ;;
  esac
}

compose_command() {
  if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    printf 'docker compose'
    return 0
  fi
  if command -v docker-compose >/dev/null 2>&1; then
    printf 'docker-compose'
    return 0
  fi
  return 1
}

check_compose_config() {
  local compose_cmd
  if ! compose_cmd="$(compose_command)"; then
    echo "Docker Compose is not installed"
    return 1
  fi
  (
    cd "$lab_root"
    # shellcheck disable=SC2086
    $compose_cmd config >/dev/null
  )
  echo "compose config is valid"
}

check_lab_env() {
  local env_path="$lab_root/artifacts/lab-env.sh"
  if [[ ! -f "$env_path" ]]; then
    echo "generated lab env is absent"
    return 10
  fi
  bash -n "$env_path"
  # shellcheck source=/dev/null
  source "$env_path"
  : "${MACBOOK_LAN_IP:?missing MACBOOK_LAN_IP}"
  : "${RIPDPI_DNS_PORT:?missing RIPDPI_DNS_PORT}"
  : "${RIPDPI_LAB_PROFILE:?missing RIPDPI_LAB_PROFILE}"
  dns_port="$RIPDPI_DNS_PORT"
  echo "profile=$RIPDPI_LAB_PROFILE host=$MACBOOK_LAN_IP dns_port=$RIPDPI_DNS_PORT"
}

check_http() {
  if ! command -v curl >/dev/null 2>&1; then
    echo "curl is not installed"
    return 1
  fi
  curl -fsS --max-time 3 "http://127.0.0.1:8080/get" >/dev/null
  echo "httpbin responded"
}

check_wiremock() {
  if ! command -v curl >/dev/null 2>&1; then
    echo "curl is not installed"
    return 1
  fi
  curl -fsS --max-time 3 "http://127.0.0.1:8082/ripdpi/ok" >/dev/null
  echo "wiremock responded"
}

check_caddy_https() {
  if ! command -v curl >/dev/null 2>&1; then
    echo "curl is not installed"
    return 1
  fi
  curl -fsSk --max-time 3 "https://127.0.0.1:8443/" >/dev/null
  echo "caddy https responded"
}

dig_query() {
  local transport="$1"
  if ! command -v dig >/dev/null 2>&1; then
    echo "dig is not installed"
    return 10
  fi

  local args=("@127.0.0.1" "-p" "$dns_port" "ok.test" "A" "+short" "+time=2" "+tries=1")
  if [[ "$transport" == "tcp" ]]; then
    args=("+tcp" "${args[@]}")
  fi

  local answer
  answer="$(dig "${args[@]}")"
  if [[ ! "$answer" =~ (^|[[:space:]])[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+($|[[:space:]]) ]]; then
    echo "unexpected DNS answer: ${answer:-empty}"
    return 1
  fi
  echo "ok.test resolved over $transport: $answer"
}

check_dns_udp() {
  dig_query "udp"
}

check_dns_tcp() {
  dig_query "tcp"
}

check_tcp_echo() {
  local payload="ripdpi-doctor-tcp"
  if command -v ruby >/dev/null 2>&1; then
    ruby -rsocket -rtimeout -e '
      payload = ARGV.fetch(0)
      data = Timeout.timeout(3) do
        sock = TCPSocket.new("127.0.0.1", 9000)
        begin
          sock.write(payload)
          sock.close_write
          sock.read
        ensure
          sock.close
        end
      end
      abort("echo mismatch: #{data.inspect}") unless data == payload
    ' "$payload"
    echo "tcp echo matched"
    return 0
  fi
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$payload" <<'PY'
import socket
import sys

payload = sys.argv[1].encode()
with socket.create_connection(("127.0.0.1", 9000), timeout=3) as sock:
    sock.sendall(payload)
    sock.shutdown(socket.SHUT_WR)
    data = sock.recv(65535)
if data != payload:
    raise SystemExit(f"echo mismatch: {data!r}")
PY
    echo "tcp echo matched"
    return 0
  fi
  echo "ruby or python3 is required for TCP echo"
  return 10
}

check_udp_echo() {
  local payload="ripdpi-doctor-udp"
  if command -v ruby >/dev/null 2>&1; then
    ruby -rsocket -rtimeout -e '
      payload = ARGV.fetch(0)
      data = Timeout.timeout(3) do
        sock = UDPSocket.new
        begin
          sock.connect("127.0.0.1", 9001)
          sock.send(payload, 0)
          sock.recvfrom(65_535).first
        ensure
          sock.close
        end
      end
      abort("echo mismatch: #{data.inspect}") unless data == payload
    ' "$payload"
    echo "udp echo matched"
    return 0
  fi
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$payload" <<'PY'
import socket
import sys

payload = sys.argv[1].encode()
sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
try:
    sock.settimeout(3)
    sock.sendto(payload, ("127.0.0.1", 9001))
    data, _ = sock.recvfrom(65535)
finally:
    sock.close()
if data != payload:
    raise SystemExit(f"echo mismatch: {data!r}")
PY
    echo "udp echo matched"
    return 0
  fi
  echo "ruby or python3 is required for UDP echo"
  return 10
}

check_adb_debug_package() {
  if ! command -v adb >/dev/null 2>&1; then
    echo "adb is not installed"
    return 10
  fi

  local serial
  serial="$(adb devices | awk 'NR > 1 && $2 == "device" {print $1; exit}')"
  if [[ -z "$serial" ]]; then
    echo "no connected adb device"
    return 10
  fi

  if ! adb -s "$serial" shell pm path com.poyka.ripdpi >/dev/null 2>&1; then
    echo "com.poyka.ripdpi is not installed on $serial"
    return 1
  fi
  if adb -s "$serial" shell run-as com.poyka.ripdpi true >/dev/null 2>&1; then
    echo "debuggable com.poyka.ripdpi is installed on $serial"
  else
    echo "com.poyka.ripdpi is installed on $serial; run-as debug check failed"
  fi
}

lab_env_path="$lab_root/artifacts/lab-env.sh"
if [[ -f "$lab_env_path" ]] && bash -n "$lab_env_path" >/dev/null 2>&1; then
  # shellcheck source=/dev/null
  source "$lab_env_path"
  dns_port="${RIPDPI_DNS_PORT:-$dns_port}"
fi

write_json() {
  mkdir -p "$(dirname "$artifact_path")"
  {
    printf '{\n'
    printf '  "generatedAt": "%s",\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    printf '  "checks": [\n'
    local index
    for index in "${!result_names[@]}"; do
      [[ "$index" == "0" ]] || printf ',\n'
      printf '    {"name":"%s","status":"%s","required":%s,"message":"%s"}' \
        "$(json_escape "${result_names[$index]}")" \
        "$(json_escape "${result_statuses[$index]}")" \
        "${result_required[$index]}" \
        "$(json_escape "${result_messages[$index]}")"
    done
    printf '\n  ]\n'
    printf '}\n'
  } > "$artifact_path"
}

run_check "compose_config" true check_compose_config
run_check "lab_env" false check_lab_env
run_check "httpbin_http" true check_http
run_check "wiremock_http" true check_wiremock
run_check "caddy_https" true check_caddy_https
run_check "dns_udp" true check_dns_udp
run_check "dns_tcp" true check_dns_tcp
run_check "tcp_echo" true check_tcp_echo
run_check "udp_echo" true check_udp_echo
run_check "adb_debug_package" false check_adb_debug_package

write_json

pass_count=0
fail_count=0
skip_count=0
required_fail_count=0
for index in "${!result_statuses[@]}"; do
  case "${result_statuses[$index]}" in
    pass) pass_count=$((pass_count + 1)) ;;
    fail)
      fail_count=$((fail_count + 1))
      if [[ "${result_required[$index]}" == "true" ]]; then
        required_fail_count=$((required_fail_count + 1))
      fi
      ;;
    skip) skip_count=$((skip_count + 1)) ;;
  esac
done

printf 'RIPDPI lab doctor: %d pass, %d fail, %d skip\n' "$pass_count" "$fail_count" "$skip_count"
printf 'Artifact: %s\n' "$artifact_path"

if [[ "$required_fail_count" -gt 0 ]]; then
  printf 'Required failures:\n'
  for index in "${!result_statuses[@]}"; do
    if [[ "${result_statuses[$index]}" == "fail" && "${result_required[$index]}" == "true" ]]; then
      printf '  - %s: %s\n' "${result_names[$index]}" "${result_messages[$index]}"
    fi
  done
  exit 1
fi
