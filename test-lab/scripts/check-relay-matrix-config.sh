#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
config_path="${RIPDPI_RELAY_MATRIX_CONFIG:-}"

required_paths=(
  mock_relay
  vless_reality
  vless_xhttp
  warp
  cloudflare_tunnel
  masque
  hysteria2
  tuic_v5
  shadowtls_v3
  naiveproxy
  webtunnel
  obfs4
  snowflake
  google_apps_script
)

required_scenarios=(
  proxy
  vpn
  diagnostics
  restart
  invalid_credentials
  server_reset
  timeout
  malformed_response
  dns_fallback
  network_handover
)

usage() {
  cat <<USAGE
Usage: $0 --config PATH

Validates the operator-provided production relay matrix manifest shape without
connecting to any provider. The manifest must not contain live endpoints or
secret values; use reference names that the private runner resolves.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --config)
      config_path="${2:?missing --config value}"
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

if [[ -z "$config_path" ]]; then
  echo "Missing relay matrix config. Set RIPDPI_RELAY_MATRIX_CONFIG or pass --config." >&2
  exit 2
fi
if [[ ! -f "$config_path" ]]; then
  echo "Relay matrix config does not exist: $config_path" >&2
  exit 2
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to validate relay matrix config" >&2
  exit 2
fi

jq -e '.version == 1 and (.relays | type == "array")' "$config_path" >/dev/null

missing_paths=()
for path in "${required_paths[@]}"; do
  if ! jq -e --arg path "$path" '.relays[]? | select(.id == $path)' "$config_path" >/dev/null; then
    missing_paths+=("$path")
  fi
done

missing_scenarios=()
for scenario in "${required_scenarios[@]}"; do
  if ! jq -e --arg scenario "$scenario" '
    [.relays[]? | select((.scenarios // []) | index($scenario) | not)] | length == 0
  ' "$config_path" >/dev/null; then
    missing_scenarios+=("$scenario")
  fi
done

invalid_entries="$(
  jq -r '
    .relays[]?
    | select((.id | type != "string") or (.kind | type != "string") or (.endpointRef | type != "string") or (.scenarios | type != "array"))
    | .id // "<missing-id>"
  ' "$config_path"
)"

duplicate_ids="$(
  jq -r '
    [.relays[]?.id | select(type == "string")]
    | group_by(.)
    | .[]
    | select(length > 1)
    | .[0]
  ' "$config_path"
)"

kind_mismatches="$(
  jq -r --argjson required "$(printf '%s\n' "${required_paths[@]}" | jq -R . | jq -s .)" '
    .relays[]?
    | select((.id | type == "string") and (.kind | type == "string"))
    | . as $relay
    | select(($required | index($relay.id)) and ($relay.kind != $relay.id))
    | $relay.id + ":" + $relay.kind
  ' "$config_path"
)"

invalid_scenarios="$(
  jq -r --argjson allowed "$(printf '%s\n' "${required_scenarios[@]}" | jq -R . | jq -s .)" '
    .relays[]? as $relay
    | ($relay.scenarios // [])[]
    | . as $scenario
    | select((type != "string") or (($allowed | index($scenario)) | not))
    | ($relay.id // "<missing-id>") + "." + ($scenario|tostring)
  ' "$config_path"
)"

duplicate_scenarios="$(
  jq -r '
    .relays[]? as $relay
    | [($relay.scenarios // [])[] | select(type == "string")]
    | group_by(.)
    | .[]
    | select(length > 1)
    | ($relay.id // "<missing-id>") + "." + .[0]
  ' "$config_path"
)"

literal_endpoint_ref_hits="$(
  jq -r '
    .relays[]? as $relay
    | ["endpointRef", "credentialRef"][] as $field
    | ($relay[$field] // empty) as $value
    | select(($value | type) == "string")
    | select($value != "none")
    | select($value | test("(?i)(://|@)"))
    | ($relay.id // "<missing-id>") + "." + $field
  ' "$config_path"
)"

sensitive_literal_hits="$(
  jq -r '
    .relays[]? as $relay
    | $relay
    | paths(scalars) as $p
    | getpath($p) as $value
    | select(($value | type) == "string")
    | select(($p[-1] | tostring | test("Ref$")) | not)
    | select($value | test("(?i)(password|token|secret|private[_-]?key|uuid=|://[^/[:space:]]+:[^/[:space:]]+@)"))
    | $relay.id + "." + ($p | map(tostring) | join("."))
  ' "$config_path"
)"

if [[ "${#missing_paths[@]}" -gt 0 ]]; then
  printf 'Missing relay paths: %s\n' "${missing_paths[*]}" >&2
  exit 1
fi
if [[ "${#missing_scenarios[@]}" -gt 0 ]]; then
  printf 'One or more relay entries is missing scenarios: %s\n' "${missing_scenarios[*]}" >&2
  exit 1
fi
if [[ -n "$invalid_entries" ]]; then
  printf 'Invalid relay entries:\n%s\n' "$invalid_entries" >&2
  exit 1
fi
if [[ -n "$duplicate_ids" ]]; then
  printf 'Duplicate relay IDs:\n%s\n' "$duplicate_ids" >&2
  exit 1
fi
if [[ -n "$kind_mismatches" ]]; then
  printf 'Relay kind must match canonical relay ID:\n%s\n' "$kind_mismatches" >&2
  exit 1
fi
if [[ -n "$invalid_scenarios" ]]; then
  printf 'Invalid relay scenarios:\n%s\n' "$invalid_scenarios" >&2
  exit 1
fi
if [[ -n "$duplicate_scenarios" ]]; then
  printf 'Duplicate relay scenarios:\n%s\n' "$duplicate_scenarios" >&2
  exit 1
fi
if [[ -n "$literal_endpoint_ref_hits" ]]; then
  printf 'Endpoint or credential refs must not contain literal URLs or userinfo:\n%s\n' "$literal_endpoint_ref_hits" >&2
  exit 1
fi
if [[ -n "$sensitive_literal_hits" ]]; then
  printf 'Potential sensitive literal values found at:\n%s\n' "$sensitive_literal_hits" >&2
  exit 1
fi

relay_count="$(jq '.relays | length' "$config_path")"
printf 'Relay matrix config valid: %s relays, %s required paths, %s required scenarios.\n' \
  "$relay_count" \
  "${#required_paths[@]}" \
  "${#required_scenarios[@]}"
printf 'Config: %s\n' "$(realpath "$config_path" 2>/dev/null || python3 -c 'import os,sys; print(os.path.abspath(sys.argv[1]))' "$config_path")"
printf 'Example: %s\n' "$repo_root/test-lab/relay/provider-matrix.example.json"
