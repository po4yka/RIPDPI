#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-readiness-test.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

default_json="$tmpdir/feature-gap-readiness.json"
duplicate_json="$tmpdir/feature-gap-readiness-duplicate.json"
relay_json="$tmpdir/feature-gap-readiness-with-relay.json"
unknown_json="$tmpdir/feature-gap-readiness-unknown-remote.json"

"$repo_root/test-lab/scripts/check-feature-gap-readiness.sh" \
  --output "$default_json" >/dev/null
"$repo_root/test-lab/scripts/check-feature-test-signoff.sh" \
  --list-required-readiness > "$tmpdir/signoff-required-readiness.txt"

python3 - "$default_json" "$tmpdir/signoff-required-readiness.txt" <<'PY'
import json
import re
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

remote = checks["remote_workflow_confirmation"]
message = remote.get("message", "")
if "Local branch" not in message or "origin/main" not in message:
    raise SystemExit(f"remote workflow message lost branch context: {message!r}")
if remote.get("status") == "blocked" and "review branch" not in message:
    raise SystemExit(f"remote workflow message lost ruleset path: {message!r}")
if re.search(r"\b[0-9a-f]{7,40}\b", message) or re.search(r"\bby \d+ commit", message):
    raise SystemExit(f"remote workflow message is commit-specific: {message!r}")
PY

python3 - "$default_json" "$duplicate_json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
data["checks"].append(dict(data["checks"][0]))
with open(sys.argv[2], "w", encoding="utf-8") as handle:
    json.dump(data, handle)
PY

set +e
python3 - "$duplicate_json" "$tmpdir/signoff-required-readiness.txt" <<'PY' > "$tmpdir/duplicate.out" 2>&1
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
    raise SystemExit("readiness/signoff required row mismatch")

checks = {}
for index, check in enumerate(data.get("checks", [])):
    name = check.get("name")
    if name in checks:
        raise SystemExit(f"duplicate readiness check: {name}")
    checks[name] = check
PY
duplicate_status=$?
set -e

if [[ "$duplicate_status" -ne 1 ]]; then
  echo "Expected duplicate readiness fixture to fail, got $duplicate_status" >&2
  cat "$tmpdir/duplicate.out" >&2
  exit 1
fi
grep -F 'duplicate readiness check: android_device' "$tmpdir/duplicate.out"

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
