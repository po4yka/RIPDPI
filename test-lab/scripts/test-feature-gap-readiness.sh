#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-readiness-test.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

default_json="$tmpdir/feature-gap-readiness.json"
relay_json="$tmpdir/feature-gap-readiness-with-relay.json"
unknown_json="$tmpdir/feature-gap-readiness-unknown-remote.json"

"$repo_root/test-lab/scripts/check-feature-gap-readiness.sh" \
  --output "$default_json" >/dev/null

python3 - "$default_json" <<'PY'
import json
import re
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)

checks = {check["name"]: check for check in data.get("checks", [])}
required = {
    "android_device",
    "rooted_physical_device",
    "manual_talkback",
    "physical_network_handover",
    "routed_netem_vm",
    "production_relay_matrix",
    "remote_workflow_confirmation",
}
missing = sorted(required.difference(checks))
if missing:
    raise SystemExit(f"missing readiness checks: {missing}")

remote = checks["remote_workflow_confirmation"]
message = remote.get("message", "")
if "Local branch" not in message or "origin/main" not in message:
    raise SystemExit(f"remote workflow message lost branch context: {message!r}")
if remote.get("status") == "blocked" and "review branch" not in message:
    raise SystemExit(f"remote workflow message lost ruleset path: {message!r}")
if re.search(r"\b[0-9a-f]{7,40}\b", message) or re.search(r"\bby \d+ commit", message):
    raise SystemExit(f"remote workflow message is commit-specific: {message!r}")
PY

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
