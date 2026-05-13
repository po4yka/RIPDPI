#!/usr/bin/env bash
set -euo pipefail

api_url="${TOXIPROXY_API_URL:-http://127.0.0.1:8474}"
proxy_filter=""

usage() {
  cat <<USAGE
Usage: $0 [--api-url URL] [--proxy NAME]

Clears active toxics from all Toxiproxy proxies, or from one proxy when
--proxy is supplied.

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
    --proxy)
      proxy_filter="${2:?missing --proxy value}"
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

require_command curl
require_command jq

api_url="${api_url%/}"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

curl_json() {
  local method="$1"
  local url="$2"
  local output="$tmp_dir/response.json"
  local status

  status="$(curl -sS -o "$output" -w '%{http_code}' -X "$method" "$url")"
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

proxies_json="$(curl_json GET "$api_url/proxies")" || {
  echo "Unable to reach Toxiproxy at $api_url. Start the lab with test-lab/scripts/start-lab.sh." >&2
  exit 1
}

if [[ -n "$proxy_filter" ]]; then
  if ! jq -e --arg proxy "$proxy_filter" 'has($proxy)' >/dev/null <<<"$proxies_json"; then
    echo "Proxy not found: $proxy_filter" >&2
    echo "Available proxies:" >&2
    jq -r 'keys[]' <<<"$proxies_json" >&2
    exit 2
  fi
  proxy_query='to_entries[] | select(.key == $proxy)'
else
  proxy_query='to_entries[]'
fi

delete_count=0
while IFS=$'\t' read -r proxy_name toxic_name; do
  [[ -n "$proxy_name" && -n "$toxic_name" ]] || continue
  echo "Deleting toxic: $proxy_name/$toxic_name"
  curl_json DELETE "$api_url/proxies/$proxy_name/toxics/$toxic_name" >/dev/null
  delete_count="$((delete_count + 1))"
done < <(jq -r --arg proxy "$proxy_filter" "$proxy_query | .key as \$proxyName | .value.toxics[]? | [\$proxyName, .name] | @tsv" <<<"$proxies_json")

if [[ "$delete_count" -eq 0 ]]; then
  echo "No active toxics found."
else
  echo "Deleted $delete_count toxic(s)."
fi

echo
echo "Current Toxiproxy state:"
curl_json GET "$api_url/proxies" | jq 'map_values({listen, upstream, enabled, toxics})'
