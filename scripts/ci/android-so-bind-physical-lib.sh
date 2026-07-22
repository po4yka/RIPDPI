#!/usr/bin/env bash

so_bind_physical_output_is_exact_pass() {
    local output_file="$1"
    local expected_class="$2"
    local expected_method="$3"

    local numtests_count class_count test_count
    numtests_count="$(grep -Fc 'INSTRUMENTATION_STATUS: numtests=' "$output_file" || true)"
    class_count="$(grep -Fc 'INSTRUMENTATION_STATUS: class=' "$output_file" || true)"
    test_count="$(grep -Fc 'INSTRUMENTATION_STATUS: test=' "$output_file" || true)"

    [[ "$numtests_count" == "2" ]] &&
        [[ "$(grep -Fxc 'INSTRUMENTATION_STATUS: numtests=1' "$output_file" || true)" == "$numtests_count" ]] &&
        [[ "$class_count" == "2" ]] &&
        [[ "$(grep -Fxc "INSTRUMENTATION_STATUS: class=$expected_class" "$output_file" || true)" == "$class_count" ]] &&
        [[ "$test_count" == "2" ]] &&
        [[ "$(grep -Fxc "INSTRUMENTATION_STATUS: test=$expected_method" "$output_file" || true)" == "$test_count" ]] &&
        [[ "$(grep -Fxc 'INSTRUMENTATION_STATUS_CODE: 1' "$output_file" || true)" == "1" ]] &&
        [[ "$(grep -Fxc 'INSTRUMENTATION_STATUS_CODE: 0' "$output_file" || true)" == "1" ]] &&
        [[ "$(grep -Fxc 'OK (1 test)' "$output_file" || true)" == "1" ]] &&
        [[ "$(grep -Fxc 'INSTRUMENTATION_CODE: -1' "$output_file" || true)" == "1" ]] &&
        [[ "$(grep -Ec '^INSTRUMENTATION_STATUS_CODE: -?[0-9]+$' "$output_file" || true)" == "2" ]] &&
        ! grep -Eqi '(^FAILURES!!!$|INSTRUMENTATION_STATUS_CODE: -|INSTRUMENTATION_RESULT: shortMsg=|Process crashed|assumption|ignored|skipped|0 tests|OK \(0 test)' "$output_file"
}

so_bind_physical_valid_fixture_host() {
    local host="${1:-}"
    [[ -n "$host" ]] || return 1
    [[ "$host" =~ ^[A-Za-z0-9._:-]+$ ]] || return 1
    [[ "$host" != -* ]] || return 1
    local lower_host
    lower_host="$(printf '%s' "$host" | tr '[:upper:]' '[:lower:]')"
    case "$lower_host" in
        localhost|localhost.*|127.*|0.0.0.0|::|::1|\[::\]|\[::1\])
            return 1
            ;;
    esac
}

so_bind_physical_valid_port() {
    local port="${1:-}"
    [[ "$port" =~ ^[0-9]+$ ]] && ((port >= 1 && port <= 65535))
}

so_bind_physical_normalize_routed_ipv6() {
    python3 - "${1:-}" <<'PY'
import ipaddress
import sys

try:
    address = ipaddress.ip_address(sys.argv[1])
except ValueError:
    raise SystemExit(1)
gua = ipaddress.ip_network("2000::/3")
ula = ipaddress.ip_network("fc00::/7")
if (
    address.version != 6
    or address.scope_id is not None
    or address.is_unspecified
    or address.is_loopback
    or address.is_link_local
    or address.is_multicast
    or address.ipv4_mapped is not None
    or (address not in gua and address not in ula)
):
    raise SystemExit(1)
print(address.compressed)
PY
}

so_bind_physical_is_underlay_interface() {
    [[ "${1:-}" =~ ^(wlan[0-9]+|rmnet[A-Za-z0-9_.-]*|ccmni[0-9]+|eth[0-9]+|usb[0-9]+|rndis[0-9]+)$ ]]
}
