#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
guard="$repo_root/test-lab/scripts/check-feature-test-signoff.sh"
tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-signoff-test.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

complete_audit="$tmpdir/complete-audit.md"
incomplete_audit="$tmpdir/incomplete-audit.md"
ready_json="$tmpdir/ready.json"
missing_required_json="$tmpdir/missing-required.json"
duplicate_required_json="$tmpdir/duplicate-required.json"
invalid_status_json="$tmpdir/invalid-status.json"
malformed_json="$tmpdir/malformed.json"
blocked_json="$tmpdir/blocked.json"

cat > "$complete_audit" <<'EOF'
# Feature Test Completion Audit

Status: **complete**.

| Requirement | Evidence inspected | Result | Remaining evidence required |
| --- | --- | --- | --- |
| All rows | Fixture evidence | Covered locally | None |
EOF

cat > "$incomplete_audit" <<'EOF'
# Feature Test Completion Audit

Status: **not complete**.

| Requirement | Evidence inspected | Result | Remaining evidence required |
| --- | --- | --- | --- |
| Remaining environment readiness | Fixture evidence | Partial | Manual rows |
EOF

cat > "$ready_json" <<'EOF'
{
  "checks": [
    {"name": "android_device", "status": "ready", "required": true, "message": "ready"},
    {"name": "rooted_physical_device", "status": "ready", "required": true, "message": "ready"},
    {"name": "manual_talkback", "status": "ready", "required": true, "message": "ready"},
    {"name": "physical_network_handover", "status": "ready", "required": true, "message": "ready"},
    {"name": "routed_netem_vm", "status": "ready", "required": true, "message": "ready"},
    {"name": "production_relay_matrix", "status": "ready", "required": true, "message": "ready"},
    {"name": "remote_workflow_confirmation", "status": "ready", "required": true, "message": "ready"},
    {"name": "optional_note", "status": "blocked", "required": false, "message": "ignored"}
  ]
}
EOF

cat > "$missing_required_json" <<'EOF'
{
  "checks": [
    {"name": "android_device", "status": "ready", "required": true, "message": "ready"}
  ]
}
EOF

cat > "$duplicate_required_json" <<'EOF'
{
  "checks": [
    {"name": "android_device", "status": "ready", "required": true, "message": "ready"},
    {"name": "android_device", "status": "ready", "required": true, "message": "duplicate"}
  ]
}
EOF

cat > "$invalid_status_json" <<'EOF'
{
  "checks": [
    {"name": "android_device", "status": "done", "required": true, "message": "invalid"}
  ]
}
EOF

printf '{"checks": [\n' > "$malformed_json"

cat > "$blocked_json" <<'EOF'
{
  "checks": [
    {"name": "android_device", "status": "ready", "required": true, "message": "ready"},
    {"name": "manual_talkback", "status": "blocked", "required": true, "message": "TalkBack inactive"},
    {"name": "physical_network_handover", "status": "manual", "required": true, "message": "Handover needs operator run"}
  ]
}
EOF

"$guard" --help > "$tmpdir/help.out"
grep -F 'Required readiness rows:' "$tmpdir/help.out"
grep -F 'rooted_physical_device' "$tmpdir/help.out"
grep -F 'remote_workflow_confirmation' "$tmpdir/help.out"
"$guard" --list-required-readiness > "$tmpdir/required-readiness.out"
grep -Fx 'android_device' "$tmpdir/required-readiness.out"
grep -Fx 'rooted_physical_device' "$tmpdir/required-readiness.out"
grep -Fx 'remote_workflow_confirmation' "$tmpdir/required-readiness.out"

"$guard" --audit "$complete_audit" --readiness "$ready_json" \
  | grep -F 'Feature test sign-off guard passed.'

set +e
"$guard" --audit "$incomplete_audit" --readiness "$blocked_json" \
  > "$tmpdir/blocked.out"
blocked_status=$?
set -e

if [[ "$blocked_status" -ne 1 ]]; then
  echo "Expected blocked sign-off to exit 1, got $blocked_status" >&2
  cat "$tmpdir/blocked.out" >&2
  exit 1
fi

grep -F 'completion audit explicitly says not complete' "$tmpdir/blocked.out"
grep -F 'completion audit still contains Partial rows' "$tmpdir/blocked.out"
grep -F 'manual_talkback is blocked: TalkBack inactive' "$tmpdir/blocked.out"
grep -F 'physical_network_handover is manual: Handover needs operator run' "$tmpdir/blocked.out"

set +e
"$guard" --audit "$complete_audit" --readiness "$missing_required_json" \
  > "$tmpdir/missing-required.out"
missing_required_status=$?
set -e

if [[ "$missing_required_status" -ne 1 ]]; then
  echo "Expected incomplete readiness artifact to exit 1, got $missing_required_status" >&2
  cat "$tmpdir/missing-required.out" >&2
  exit 1
fi

grep -F 'manual_talkback is missing: required readiness check is absent from the artifact' \
  "$tmpdir/missing-required.out"
grep -F 'remote_workflow_confirmation is missing: required readiness check is absent from the artifact' \
  "$tmpdir/missing-required.out"

set +e
"$guard" --audit "$complete_audit" --readiness "$duplicate_required_json" \
  > "$tmpdir/duplicate-required.out"
duplicate_required_status=$?
set -e

if [[ "$duplicate_required_status" -ne 1 ]]; then
  echo "Expected duplicate readiness artifact to exit 1, got $duplicate_required_status" >&2
  cat "$tmpdir/duplicate-required.out" >&2
  exit 1
fi

grep -F 'android_device is invalid: duplicate required readiness check' \
  "$tmpdir/duplicate-required.out"

set +e
"$guard" --audit "$complete_audit" --readiness "$invalid_status_json" \
  > "$tmpdir/invalid-status.out"
invalid_status_status=$?
set -e

if [[ "$invalid_status_status" -ne 1 ]]; then
  echo "Expected invalid status readiness artifact to exit 1, got $invalid_status_status" >&2
  cat "$tmpdir/invalid-status.out" >&2
  exit 1
fi

grep -F 'android_device is invalid: status must be one of' "$tmpdir/invalid-status.out"

set +e
"$guard" --audit "$complete_audit" --readiness "$malformed_json" \
  > "$tmpdir/malformed.out"
malformed_status=$?
set -e

if [[ "$malformed_status" -ne 1 ]]; then
  echo "Expected malformed readiness artifact to exit 1, got $malformed_status" >&2
  cat "$tmpdir/malformed.out" >&2
  exit 1
fi

grep -F 'readiness_artifact is invalid: could not parse readiness JSON' \
  "$tmpdir/malformed.out"

set +e
"$guard" --audit "$complete_audit" --readiness "$tmpdir/missing.json" \
  > "$tmpdir/missing.out" 2>&1
missing_status=$?
set -e

if [[ "$missing_status" -ne 2 ]]; then
  echo "Expected missing readiness artifact to exit 2, got $missing_status" >&2
  cat "$tmpdir/missing.out" >&2
  exit 1
fi

grep -F 'Readiness artifact not found' "$tmpdir/missing.out"

echo "Feature test sign-off guard self-test passed."
