#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=test-lab/scripts/python.sh
source "$repo_root/test-lab/scripts/python.sh"
python_bin="$(ripdpi_resolve_python "relay matrix config tests")"
validator="$repo_root/test-lab/scripts/check-relay-matrix-config.sh"
example="$repo_root/test-lab/relay/provider-matrix.example.json"
tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-relay-matrix-test.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

output="$tmpdir/output.txt"

"$validator" --config "$example" >/dev/null
"$validator" --list-required-paths > "$tmpdir/required-paths.txt"
"$validator" --list-required-scenarios > "$tmpdir/required-scenarios.txt"
grep -Fxq "mock_relay" "$tmpdir/required-paths.txt"
grep -Fxq "google_apps_script" "$tmpdir/required-paths.txt"
grep -Fxq "proxy" "$tmpdir/required-scenarios.txt"
grep -Fxq "network_handover" "$tmpdir/required-scenarios.txt"

"$python_bin" - "$repo_root/docs/feature-test-manual-evidence-template.md" \
  "$tmpdir/required-paths.txt" <<'PY'
import re
import sys
from pathlib import Path

template = Path(sys.argv[1]).read_text(encoding="utf-8")
required = {
    line.strip()
    for line in Path(sys.argv[2]).read_text(encoding="utf-8").splitlines()
    if line.strip()
}

section_match = re.search(
    r"## Provider Relay Matrix\n(?P<body>.*?)\n## TalkBack",
    template,
    re.S,
)
if section_match is None:
    raise SystemExit("manual evidence template is missing the provider relay matrix section")

documented = set()
for line in section_match.group("body").splitlines():
    match = re.match(r"^\|\s*([a-z0-9_]+)\s*\|", line)
    if match and match.group(1) != "Relay":
        documented.add(match.group(1))

if documented != required:
    raise SystemExit(
        "manual evidence relay rows do not match relay validator: "
        f"documented={sorted(documented)!r} required={sorted(required)!r}"
    )
PY

expect_failure() {
  local config="$1"
  local expected="$2"
  set +e
  "$validator" --config "$config" >"$output" 2>&1
  local status=$?
  set -e
  cat "$output"
  if [[ "$status" -eq 0 ]]; then
    echo "Expected relay matrix validation to fail: $expected" >&2
    exit 1
  fi
  grep -F "$expected" "$output"
}

duplicate_ids="$tmpdir/duplicate-ids.json"
jq '.relays += [.relays[0]]' "$example" > "$duplicate_ids"
expect_failure "$duplicate_ids" "Duplicate relay IDs:"
grep -F "mock_relay" "$output"

kind_mismatch="$tmpdir/kind-mismatch.json"
jq '.relays[1].kind = "mock_relay"' "$example" > "$kind_mismatch"
expect_failure "$kind_mismatch" "Relay kind must match canonical relay ID:"
grep -F "vless_reality:mock_relay" "$output"

unknown_scenario="$tmpdir/unknown-scenario.json"
jq '.relays[0].scenarios += ["unexpected_scenario"]' "$example" > "$unknown_scenario"
expect_failure "$unknown_scenario" "Invalid relay scenarios:"
grep -F "mock_relay.unexpected_scenario" "$output"

non_string_scenario="$tmpdir/non-string-scenario.json"
jq '.relays[0].scenarios += [42]' "$example" > "$non_string_scenario"
expect_failure "$non_string_scenario" "Invalid relay scenarios:"
grep -F "mock_relay.42" "$output"

duplicate_scenario="$tmpdir/duplicate-scenario.json"
jq '.relays[0].scenarios += ["proxy"]' "$example" > "$duplicate_scenario"
expect_failure "$duplicate_scenario" "Duplicate relay scenarios:"
grep -F "mock_relay.proxy" "$output"

literal_endpoint="$tmpdir/literal-endpoint.json"
jq '.relays[1].endpointRef = "https://relay.example.test/path"' "$example" > "$literal_endpoint"
expect_failure "$literal_endpoint" "Endpoint or credential refs must not contain literal URLs or userinfo:"
grep -F "vless_reality.endpointRef" "$output"

literal_userinfo="$tmpdir/literal-userinfo.json"
jq '.relays[1].credentialRef = "user:pass@relay.example.test"' "$example" > "$literal_userinfo"
expect_failure "$literal_userinfo" "Endpoint or credential refs must not contain literal URLs or userinfo:"
grep -F "vless_reality.credentialRef" "$output"

literal_sensitive="$tmpdir/literal-sensitive.json"
jq '.relays[1].notes = "uuid=00000000-0000-0000-0000-000000000000"' "$example" > "$literal_sensitive"
expect_failure "$literal_sensitive" "Potential sensitive literal values found at:"
grep -F "vless_reality.notes" "$output"

echo "Relay matrix config self-test passed."
