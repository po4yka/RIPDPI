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
jq -e '.initialTransportRaceScenarios | map(.id) | sort == ["tcp_application_blackhole_udp_healthy", "udp_drop_reality_healthy"]' "$example" >/dev/null

"$python_bin" - "$repo_root/docs/feature-test-manual-evidence-template.md" \
  "$tmpdir/required-paths.txt" \
  "$repo_root/core/data/settings/src/main/kotlin/com/poyka/ripdpi/data/RelaySettings.kt" <<'PY'
import re
import sys
from pathlib import Path

template = Path(sys.argv[1]).read_text(encoding="utf-8")
required = {
    line.strip()
    for line in Path(sys.argv[2]).read_text(encoding="utf-8").splitlines()
    if line.strip()
}
relay_settings = Path(sys.argv[3]).read_text(encoding="utf-8")

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

missing = required - documented
if missing:
    raise SystemExit(
        "manual evidence template is missing relay rows required by the validator: "
        f"missing={sorted(missing)!r} documented={sorted(documented)!r}"
    )

supported = set(re.findall(r'^const val RelayKind\w+ = "([a-z0-9_]+)"$', relay_settings, re.M))
supported.add("mock_relay")
allowed = supported | required
unknown = documented - allowed
if unknown:
    raise SystemExit(
        "manual evidence template has relay rows absent from both the current relay inventory and provider contract: "
        f"unknown={sorted(unknown)!r} allowed={sorted(allowed)!r}"
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

missing_initial_race="$tmpdir/missing-initial-race.json"
jq 'del(.initialTransportRaceScenarios[0])' "$example" > "$missing_initial_race"
expect_failure "$missing_initial_race" "Missing initial transport race scenarios:"
grep -F "tcp_application_blackhole_udp_healthy" "$output"

invalid_initial_winner="$tmpdir/invalid-initial-winner.json"
jq '.initialTransportRaceScenarios[0].expectedWinner = "unknown"' "$example" > "$invalid_initial_winner"
expect_failure "$invalid_initial_winner" "Invalid initial transport race scenarios:"
grep -F "tcp_application_blackhole_udp_healthy" "$output"

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
