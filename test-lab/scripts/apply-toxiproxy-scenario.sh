#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
api_url="${TOXIPROXY_API_URL:-http://127.0.0.1:8474}"
scenario=""

usage() {
  cat <<USAGE
Usage: $0 [--api-url URL] <scenario-name-or-json>

Examples:
  $0 latency
  $0 timeout
  $0 reset
  $0 test-lab/chaos/toxiproxy/scenarios/latency.json

Environment:
  TOXIPROXY_API_URL  Toxiproxy API URL, default: http://127.0.0.1:8474
USAGE
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "$1 is required. Install it and retry." >&2
    exit 127
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --api-url)
      api_url="${2:?missing --api-url value}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      if [[ -n "$scenario" ]]; then
        echo "Only one scenario may be supplied." >&2
        usage >&2
        exit 2
      fi
      scenario="$1"
      shift
      ;;
  esac
done

if [[ -z "$scenario" ]]; then
  echo "Missing scenario." >&2
  usage >&2
  exit 2
fi

require_command curl
require_command jq

scenario_path="$scenario"
if [[ ! -f "$scenario_path" ]]; then
  scenario_path="$lab_root/chaos/toxiproxy/scenarios/${scenario%.json}.json"
fi
if [[ ! -f "$scenario_path" ]]; then
  echo "Scenario not found: $scenario" >&2
  echo "Available scenarios:" >&2
  find "$lab_root/chaos/toxiproxy/scenarios" -maxdepth 1 -name '*.json' -exec basename {} .json \; | sort >&2
  exit 2
fi

api_url="${api_url%/}"
proxy_name="$(jq -er '.proxy' "$scenario_path")"
toxic_count="$(jq -er '.toxics | length' "$scenario_path")"

if [[ "$toxic_count" -eq 0 ]]; then
  echo "Scenario has no toxics: $scenario_path" >&2
  exit 2
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

curl_json() {
  local method="$1"
  local url="$2"
  local body="${3:-}"
  local output="$tmp_dir/response.json"
  local status

  if [[ -n "$body" ]]; then
    status="$(curl -sS -o "$output" -w '%{http_code}' -X "$method" -H 'Content-Type: application/json' --data "$body" "$url")"
  else
    status="$(curl -sS -o "$output" -w '%{http_code}' -X "$method" "$url")"
  fi

  if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
    echo "Toxiproxy API request failed: $method $url -> HTTP $status" >&2
    if [[ -s "$output" ]]; then
      cat "$output" >&2
      echo >&2
    fi
    return 1
  fi

  cat "$output"
}

curl_allow_404() {
  local method="$1"
  local url="$2"
  local output="$tmp_dir/response.json"
  local status

  status="$(curl -sS -o "$output" -w '%{http_code}' -X "$method" "$url")"
  if [[ "$status" == "404" ]]; then
    return 0
  fi
  if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
    echo "Toxiproxy API request failed: $method $url -> HTTP $status" >&2
    if [[ -s "$output" ]]; then
      cat "$output" >&2
      echo >&2
    fi
    return 1
  fi
}

if ! curl_json GET "$api_url/proxies/$proxy_name" >/dev/null; then
  echo "Proxy '$proxy_name' is unavailable. Start the lab with test-lab/scripts/start-lab.sh." >&2
  exit 1
fi

echo "Applying scenario: $scenario_path"
echo "Toxiproxy API: $api_url"
echo "Proxy: $proxy_name"

while IFS= read -r toxic_name; do
  echo "Clearing existing toxic if present: $toxic_name"
  curl_allow_404 DELETE "$api_url/proxies/$proxy_name/toxics/$toxic_name"
done < <(jq -r '.toxics[].name' "$scenario_path")

for index in $(seq 0 "$((toxic_count - 1))"); do
  toxic_body="$(jq -c ".toxics[$index]" "$scenario_path")"
  toxic_name="$(jq -r '.name' <<<"$toxic_body")"
  echo "Adding toxic: $toxic_name"
  curl_json POST "$api_url/proxies/$proxy_name/toxics" "$toxic_body" >/dev/null
done

echo
echo "Active toxics for $proxy_name:"
curl_json GET "$api_url/proxies/$proxy_name" | jq '{name, listen, upstream, enabled, toxics}'
