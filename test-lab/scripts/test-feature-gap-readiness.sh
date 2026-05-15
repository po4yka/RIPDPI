#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-readiness-test.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

default_json="$tmpdir/feature-gap-readiness.json"
duplicate_json="$tmpdir/feature-gap-readiness-duplicate.json"
invalid_status_json="$tmpdir/feature-gap-readiness-invalid-status.json"
invalid_required_json="$tmpdir/feature-gap-readiness-invalid-required.json"
unexpected_json="$tmpdir/feature-gap-readiness-unexpected.json"
relay_json="$tmpdir/feature-gap-readiness-with-relay.json"
unknown_json="$tmpdir/feature-gap-readiness-unknown-remote.json"
validator="$tmpdir/validate-readiness.py"

"$repo_root/test-lab/scripts/check-feature-gap-readiness.sh" \
  --output "$default_json" >/dev/null
"$repo_root/test-lab/scripts/check-feature-test-signoff.sh" \
  --list-required-readiness > "$tmpdir/signoff-required-readiness.txt"

cat > "$validator" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
with open(sys.argv[2], encoding="utf-8") as handle:
    signoff_required = {line.strip() for line in handle if line.strip()}

required = {
    "android_device",
    "rooted_physical_device",
    "manual_talkback",
    "physical_network_handover",
    "routed_netem_vm",
    "production_relay_matrix",
    "remote_workflow_confirmation",
}
if signoff_required != required:
    raise SystemExit(
        "readiness/signoff required row mismatch: "
        f"readiness={sorted(required)!r} signoff={sorted(signoff_required)!r}"
    )
if not isinstance(data, dict):
    raise SystemExit("readiness artifact must be a JSON object")
checks_list = data.get("checks")
if not isinstance(checks_list, list):
    raise SystemExit("readiness artifact checks must be an array")

checks = {}
allowed_statuses = {"ready", "manual", "blocked"}
for index, check in enumerate(checks_list):
    if not isinstance(check, dict):
        raise SystemExit(f"readiness checks[{index}] must be an object")
    name = check.get("name")
    status = check.get("status")
    required_value = check.get("required")
    message = check.get("message")
    if not isinstance(name, str) or not name:
        raise SystemExit(f"readiness checks[{index}].name must be a non-empty string")
    if name in checks:
        raise SystemExit(f"duplicate readiness check: {name}")
    if status not in allowed_statuses:
        raise SystemExit(
            f"{name} readiness status must be one of {sorted(allowed_statuses)}, "
            f"got {status!r}"
        )
    if required_value is not True and required_value is not False:
        raise SystemExit(f"{name} readiness required must be a boolean")
    if not isinstance(message, str):
        raise SystemExit(f"{name} readiness message must be a string")
    checks[name] = check

actual_required = {name for name, check in checks.items() if check.get("required") is True}
if actual_required != required:
    raise SystemExit(
        "readiness artifact required row mismatch: "
        f"actual={sorted(actual_required)!r} required={sorted(required)!r}"
    )
extra = sorted(set(checks).difference(required))
if extra:
    raise SystemExit(f"unexpected readiness checks: {extra}")
PY

python3 "$validator" "$default_json" "$tmpdir/signoff-required-readiness.txt"

python3 - "$default_json" <<'PY'
import json
import re
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
checks = {check["name"]: check for check in data.get("checks", [])}

remote = checks["remote_workflow_confirmation"]
message = remote.get("message", "")
if "Local branch" not in message or "origin/main" not in message:
    raise SystemExit(f"remote workflow message lost branch context: {message!r}")
if remote.get("status") == "blocked" and "review branch" not in message:
    raise SystemExit(f"remote workflow message lost ruleset path: {message!r}")
if re.search(r"\b[0-9a-f]{7,40}\b", message) or re.search(r"\bby \d+ commit", message):
    raise SystemExit(f"remote workflow message is commit-specific: {message!r}")
PY

make_fixture() {
  local output="$1"
  local mutation="$2"
  python3 - "$default_json" "$output" "$mutation" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
mutation = sys.argv[3]
if mutation == "duplicate":
    data["checks"].append(dict(data["checks"][0]))
elif mutation == "invalid_status":
    data["checks"][0]["status"] = "done"
elif mutation == "invalid_required":
    data["checks"][0]["required"] = "true"
elif mutation == "unexpected":
    data["checks"].append(
        {
            "name": "unexpected_check",
            "status": "ready",
            "required": False,
            "message": "unexpected",
        }
    )
else:
    raise SystemExit(f"unknown readiness fixture mutation: {mutation}")
with open(sys.argv[2], "w", encoding="utf-8") as handle:
    json.dump(data, handle)
PY
}

expect_invalid_readiness() {
  local fixture="$1"
  local expected="$2"
  set +e
  python3 "$validator" "$fixture" "$tmpdir/signoff-required-readiness.txt" \
    > "$tmpdir/invalid-readiness.out" 2>&1
  local status=$?
  set -e
  cat "$tmpdir/invalid-readiness.out"
  if [[ "$status" -ne 1 ]]; then
    echo "Expected invalid readiness fixture to fail, got $status" >&2
    cat "$tmpdir/invalid-readiness.out" >&2
    exit 1
  fi
  grep -F "$expected" "$tmpdir/invalid-readiness.out"
}

make_fixture "$duplicate_json" "duplicate"
expect_invalid_readiness "$duplicate_json" "duplicate readiness check: android_device"
make_fixture "$invalid_status_json" "invalid_status"
expect_invalid_readiness "$invalid_status_json" "android_device readiness status must be one of"
make_fixture "$invalid_required_json" "invalid_required"
expect_invalid_readiness "$invalid_required_json" "android_device readiness required must be a boolean"
make_fixture "$unexpected_json" "unexpected"
expect_invalid_readiness "$unexpected_json" "unexpected readiness checks: ['unexpected_check']"

RIPDPI_REMOTE_COMPARE_REF="origin/ripdpi-missing-test-ref" \
  "$repo_root/test-lab/scripts/check-feature-gap-readiness.sh" \
  --output "$unknown_json" >/dev/null

python3 - "$unknown_json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)

checks = {check["name"]: check for check in data.get("checks", [])}
remote = checks.get("remote_workflow_confirmation")
if remote is None:
    raise SystemExit("missing remote_workflow_confirmation readiness check")
if remote.get("status") != "blocked":
    raise SystemExit(f"expected unknown remote compare to block, got {remote.get('status')!r}")
message = remote.get("message", "")
if "Could not compare" not in message or "origin/ripdpi-missing-test-ref" not in message:
    raise SystemExit(f"unknown remote compare message is unclear: {message!r}")
PY

RIPDPI_RELAY_MATRIX_CONFIG="$repo_root/test-lab/relay/provider-matrix.example.json" \
  "$repo_root/test-lab/scripts/check-feature-gap-readiness.sh" \
  --output "$relay_json" >/dev/null

python3 - "$relay_json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)

checks = {check["name"]: check for check in data.get("checks", [])}
relay = checks.get("production_relay_matrix")
if relay is None:
    raise SystemExit("missing production_relay_matrix readiness check")
if relay.get("status") != "manual":
    raise SystemExit(
        "expected valid relay matrix to produce manual status, "
        f"got {relay.get('status')!r}: {relay.get('message', '')}"
    )
PY

echo "Feature gap readiness self-test passed."
