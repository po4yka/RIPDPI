#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
guard="$repo_root/test-lab/scripts/check-feature-test-signoff.sh"
tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-signoff-test.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

# shellcheck source=test-lab/scripts/python.sh
source "$repo_root/test-lab/scripts/python.sh"

python_bin="$(ripdpi_resolve_python "feature sign-off tests")"

if [[ -x /bin/bash ]]; then
  /bin/bash -n "$guard"
fi

complete_audit="$tmpdir/complete-audit.md"
missing_audit_row="$tmpdir/missing-audit-row.md"
audit_remaining_work="$tmpdir/audit-remaining-work.md"
incomplete_audit="$tmpdir/incomplete-audit.md"
ready_json="$tmpdir/ready.json"
stale_json="$tmpdir/stale.json"
missing_timestamp_json="$tmpdir/missing-timestamp.json"
future_json="$tmpdir/future.json"
non_object_json="$tmpdir/non-object.json"
missing_checks_json="$tmpdir/missing-checks.json"
invalid_row_schema_json="$tmpdir/invalid-row-schema.json"
missing_required_json="$tmpdir/missing-required.json"
duplicate_required_json="$tmpdir/duplicate-required.json"
invalid_status_json="$tmpdir/invalid-status.json"
malformed_json="$tmpdir/malformed.json"
blocked_json="$tmpdir/blocked.json"
multiline_message_json="$tmpdir/multiline-message.json"
current_epoch="$(date +%s)"

cat > "$complete_audit" <<'EOF'
# Feature Test Completion Audit

Status: **complete**.

| Requirement | Evidence inspected | Result | Remaining evidence required |
| --- | --- | --- | --- |
| Use `docs/feature-test-checklist.md` as the source checklist | Fixture evidence | Covered locally | None |
| Fix all issues found during the local pass | Fixture evidence | Covered locally | None |
| Verify Appium installation and current app flows | Fixture evidence | Covered locally | None |
| Verify Maestro installation and current smoke flows | Fixture evidence | Covered locally | None |
| Verify static local quality gates for the current head | Fixture evidence | Covered locally | None |
| Verify local artifacts referenced by the evidence ledger exist | Fixture evidence | Covered locally | None |
| Verify remaining environment readiness | Fixture evidence | Covered locally | None |
| Verify rooted behavior | Fixture evidence | Covered locally | None |
| Verify physical network matrix | Fixture evidence | Covered locally | None |
| Verify relay provider matrix | Fixture evidence | Covered locally | None |
| Verify accessibility with TalkBack | Fixture evidence | Covered locally | None |
| Verify routed VM packet-loss lab | Fixture evidence | Covered locally | None |
| Verify remote release gates | Fixture evidence | Covered locally | None |
EOF

cat > "$missing_audit_row" <<'EOF'
# Feature Test Completion Audit

Status: **complete**.

| Requirement | Evidence inspected | Result | Remaining evidence required |
| --- | --- | --- | --- |
| Use `docs/feature-test-checklist.md` as the source checklist | Fixture evidence | Covered locally | None |
| Fix all issues found during the local pass | Fixture evidence | Covered locally | None |
| Verify Appium installation and current app flows | Fixture evidence | Covered locally | None |
| Verify Maestro installation and current smoke flows | Fixture evidence | Covered locally | None |
| Verify static local quality gates for the current head | Fixture evidence | Covered locally | None |
| Verify local artifacts referenced by the evidence ledger exist | Fixture evidence | Covered locally | None |
| Verify remaining environment readiness | Fixture evidence | Covered locally | None |
| Verify rooted behavior | Fixture evidence | Covered locally | None |
| Verify physical network matrix | Fixture evidence | Covered locally | None |
| Verify relay provider matrix | Fixture evidence | Covered locally | None |
| Verify accessibility with TalkBack | Fixture evidence | Covered locally | None |
| Verify routed VM packet-loss lab | Fixture evidence | Covered locally | None |
EOF

cat > "$incomplete_audit" <<'EOF'
# Feature Test Completion Audit

Status: **not complete**.

| Requirement | Evidence inspected | Result | Remaining evidence required |
| --- | --- | --- | --- |
| Remaining environment readiness | Fixture evidence | Partial | Manual rows |
EOF

cp "$complete_audit" "$audit_remaining_work"
perl -0pi -e \
  's/(\| Verify remote release gates \| Fixture evidence \| Covered locally \| )None \|/${1}Fresh remote workflows |/' \
  "$audit_remaining_work"

cat > "$ready_json" <<EOF
{
  "generatedAtEpoch": $current_epoch,
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

cat > "$stale_json" <<'EOF'
{
  "generatedAtEpoch": 1,
  "checks": [
    {"name": "android_device", "status": "ready", "required": true, "message": "ready"},
    {"name": "rooted_physical_device", "status": "ready", "required": true, "message": "ready"},
    {"name": "manual_talkback", "status": "ready", "required": true, "message": "ready"},
    {"name": "physical_network_handover", "status": "ready", "required": true, "message": "ready"},
    {"name": "routed_netem_vm", "status": "ready", "required": true, "message": "ready"},
    {"name": "production_relay_matrix", "status": "ready", "required": true, "message": "ready"},
    {"name": "remote_workflow_confirmation", "status": "ready", "required": true, "message": "ready"}
  ]
}
EOF

cat > "$missing_timestamp_json" <<'EOF'
{
  "checks": [
    {"name": "android_device", "status": "ready", "required": true, "message": "ready"},
    {"name": "rooted_physical_device", "status": "ready", "required": true, "message": "ready"},
    {"name": "manual_talkback", "status": "ready", "required": true, "message": "ready"},
    {"name": "physical_network_handover", "status": "ready", "required": true, "message": "ready"},
    {"name": "routed_netem_vm", "status": "ready", "required": true, "message": "ready"},
    {"name": "production_relay_matrix", "status": "ready", "required": true, "message": "ready"},
    {"name": "remote_workflow_confirmation", "status": "ready", "required": true, "message": "ready"}
  ]
}
EOF

cat > "$future_json" <<EOF
{
  "generatedAtEpoch": $((current_epoch + 600)),
  "checks": [
    {"name": "android_device", "status": "ready", "required": true, "message": "ready"},
    {"name": "rooted_physical_device", "status": "ready", "required": true, "message": "ready"},
    {"name": "manual_talkback", "status": "ready", "required": true, "message": "ready"},
    {"name": "physical_network_handover", "status": "ready", "required": true, "message": "ready"},
    {"name": "routed_netem_vm", "status": "ready", "required": true, "message": "ready"},
    {"name": "production_relay_matrix", "status": "ready", "required": true, "message": "ready"},
    {"name": "remote_workflow_confirmation", "status": "ready", "required": true, "message": "ready"}
  ]
}
EOF

cat > "$non_object_json" <<EOF
[
  {"generatedAtEpoch": $current_epoch}
]
EOF

cat > "$missing_checks_json" <<EOF
{
  "generatedAtEpoch": $current_epoch,
  "checks": {}
}
EOF

cat > "$invalid_row_schema_json" <<EOF
{
  "generatedAtEpoch": $current_epoch,
  "checks": [
    42,
    {"name": "", "status": "ready", "required": true, "message": "empty name"},
    {"name": "android_device", "status": "ready", "required": "true", "message": "bad required"},
    {"name": "rooted_physical_device", "status": "ready", "required": true, "message": 42}
  ]
}
EOF

cat > "$missing_required_json" <<EOF
{
  "generatedAtEpoch": $current_epoch,
  "checks": [
    {"name": "android_device", "status": "ready", "required": true, "message": "ready"}
  ]
}
EOF

cat > "$duplicate_required_json" <<EOF
{
  "generatedAtEpoch": $current_epoch,
  "checks": [
    {"name": "android_device", "status": "ready", "required": true, "message": "ready"},
    {"name": "android_device", "status": "ready", "required": true, "message": "duplicate"}
  ]
}
EOF

cat > "$invalid_status_json" <<EOF
{
  "generatedAtEpoch": $current_epoch,
  "checks": [
    {"name": "android_device", "status": "done", "required": true, "message": "invalid"}
  ]
}
EOF

printf '{"checks": [\n' > "$malformed_json"

cat > "$blocked_json" <<EOF
{
  "generatedAtEpoch": $current_epoch,
  "checks": [
    {"name": "android_device", "status": "ready", "required": true, "message": "ready"},
    {"name": "manual_talkback", "status": "blocked", "required": true, "message": "TalkBack inactive"},
    {"name": "physical_network_handover", "status": "manual", "required": true, "message": "Handover needs operator run"}
  ]
}
EOF

cat > "$multiline_message_json" <<EOF
{
  "generatedAtEpoch": $current_epoch,
  "checks": [
    {"name": "android_device", "status": "ready", "required": true, "message": "ready"},
    {"name": "rooted_physical_device", "status": "ready", "required": true, "message": "ready"},
    {"name": "manual_talkback", "status": "ready", "required": true, "message": "ready"},
    {"name": "physical_network_handover", "status": "ready", "required": true, "message": "ready"},
    {"name": "routed_netem_vm", "status": "ready", "required": true, "message": "ready"},
    {"name": "production_relay_matrix", "status": "manual", "required": true, "message": "Relay matrix config is valid.\\nConfig: /tmp/provider-matrix.json\\nExample: test-lab/relay/provider-matrix.example.json"},
    {"name": "remote_workflow_confirmation", "status": "ready", "required": true, "message": "ready"}
  ]
}
EOF

"$guard" --help > "$tmpdir/help.out"
grep -F 'Required readiness rows:' "$tmpdir/help.out"
grep -F 'Required completion-audit rows:' "$tmpdir/help.out"
grep -F 'rooted_physical_device' "$tmpdir/help.out"
grep -F 'remote_workflow_confirmation' "$tmpdir/help.out"
grep -F 'Verify remote release gates' "$tmpdir/help.out"
"$guard" --list-required-readiness > "$tmpdir/required-readiness.out"
grep -Fx 'android_device' "$tmpdir/required-readiness.out"
grep -Fx 'rooted_physical_device' "$tmpdir/required-readiness.out"
grep -Fx 'remote_workflow_confirmation' "$tmpdir/required-readiness.out"
"$guard" --list-required-audit-rows > "$tmpdir/required-audit-rows.out"
grep -Fx 'Use docs/feature-test-checklist.md as the source checklist' \
  "$tmpdir/required-audit-rows.out"
grep -Fx 'Verify remote release gates' "$tmpdir/required-audit-rows.out"

"$python_bin" - "$repo_root/docs/feature-test-manual-evidence-template.md" \
  "$repo_root/docs/testing.md" \
  "$repo_root/test-lab/README.md" \
  "$tmpdir/required-readiness.out" <<'PY'
import re
import sys
from pathlib import Path

template = Path(sys.argv[1]).read_text(encoding="utf-8")
testing_guide = Path(sys.argv[2]).read_text(encoding="utf-8")
test_lab_readme = Path(sys.argv[3]).read_text(encoding="utf-8")
required = {
    line.strip()
    for line in Path(sys.argv[4]).read_text(encoding="utf-8").splitlines()
    if line.strip()
}

section_match = re.search(
    r"## Operator-Reviewed Readiness JSON\n(?P<body>.*?)\n## Final Verdict",
    template,
    re.S,
)
if section_match is None:
    raise SystemExit("manual evidence template is missing the operator-reviewed readiness section")

documented = set()
for line in section_match.group("body").splitlines():
    match = re.match(r"^\|\s*`([^`]+)`\s*\|", line)
    if match:
        documented.add(match.group(1))

if documented != required:
    raise SystemExit(
        "manual evidence readiness rows do not match sign-off guard: "
        f"documented={sorted(documented)!r} required={sorted(required)!r}"
    )

workflow_section_match = re.search(
    r"## Remote Workflows\n(?P<body>.*?)\n## Operator-Reviewed Readiness JSON",
    template,
    re.S,
)
if workflow_section_match is None:
    raise SystemExit("manual evidence template is missing the remote workflows section")

required_workflows = {
    "CI",
    "CodeQL",
    "local-network-lab",
    "offline-analytics",
    "mutation-testing",
    "Fuzz Nightly",
}
documented_workflows = set()
for line in workflow_section_match.group("body").splitlines():
    match = re.match(r"^\|\s*([^|]+?)\s*\|", line)
    if match:
        value = match.group(1).strip()
        if value not in {"Workflow", "---"}:
            documented_workflows.add(value)

missing_workflows = sorted(required_workflows - documented_workflows)
if missing_workflows:
    raise SystemExit(
        "manual evidence template is missing remote workflow rows: "
        f"{missing_workflows!r}"
    )

required_dispatch_commands = {
    "gh workflow run ci.yml --ref main",
    "gh workflow run local-network-lab.yml --ref main -f run_vpn_emulator_lane=false",
    "gh workflow run offline-analytics.yml --ref main -f private_corpus_path=''",
    "gh workflow run mutation-testing.yml --ref main -f packages='' -f in_diff=false",
    "gh workflow run fuzz-nightly.yml --ref main -f fuzz_seconds=1800",
}
for doc_name, doc_text in {
    "docs/testing.md": testing_guide,
    "test-lab/README.md": test_lab_readme,
}.items():
    missing_commands = sorted(
        command
        for command in required_dispatch_commands
        if command not in doc_text
    )
    if missing_commands:
        raise SystemExit(
            f"{doc_name} is missing remote workflow dispatch commands: "
            f"{missing_commands!r}"
        )
    if "CodeQL" not in doc_text or "push" not in doc_text:
        raise SystemExit(f"{doc_name} must document CodeQL as a push-triggered run")
PY

"$guard" --audit "$complete_audit" --readiness "$ready_json" \
  | grep -F 'Feature test sign-off guard passed.'

set +e
"$guard" --audit "$missing_audit_row" --readiness "$ready_json" \
  > "$tmpdir/missing-audit-row.out"
missing_audit_row_status=$?
set -e

if [[ "$missing_audit_row_status" -ne 1 ]]; then
  echo "Expected missing audit row to exit 1, got $missing_audit_row_status" >&2
  cat "$tmpdir/missing-audit-row.out" >&2
  exit 1
fi

grep -F 'completion audit is missing required row: Verify remote release gates' \
  "$tmpdir/missing-audit-row.out"

set +e
"$guard" --audit "$complete_audit" --readiness "$stale_json" \
  > "$tmpdir/stale.out"
stale_status=$?
set -e

if [[ "$stale_status" -ne 1 ]]; then
  echo "Expected stale readiness artifact to exit 1, got $stale_status" >&2
  cat "$tmpdir/stale.out" >&2
  exit 1
fi

grep -F 'readiness_artifact is stale: generatedAtEpoch is' "$tmpdir/stale.out"

set +e
"$guard" --audit "$complete_audit" --readiness "$missing_timestamp_json" \
  > "$tmpdir/missing-timestamp.out"
missing_timestamp_status=$?
set -e

if [[ "$missing_timestamp_status" -ne 1 ]]; then
  echo "Expected missing readiness timestamp to exit 1, got $missing_timestamp_status" >&2
  cat "$tmpdir/missing-timestamp.out" >&2
  exit 1
fi

grep -F 'readiness_artifact is invalid: generatedAtEpoch must be an integer' \
  "$tmpdir/missing-timestamp.out"

set +e
"$guard" --audit "$complete_audit" --readiness "$future_json" \
  > "$tmpdir/future.out"
future_status=$?
set -e

if [[ "$future_status" -ne 1 ]]; then
  echo "Expected future readiness timestamp to exit 1, got $future_status" >&2
  cat "$tmpdir/future.out" >&2
  exit 1
fi

grep -F 'readiness_artifact is invalid: generatedAtEpoch is in the future' \
  "$tmpdir/future.out"

set +e
"$guard" --audit "$complete_audit" --readiness "$non_object_json" \
  > "$tmpdir/non-object.out"
non_object_status=$?
set -e

if [[ "$non_object_status" -ne 1 ]]; then
  echo "Expected non-object readiness artifact to exit 1, got $non_object_status" >&2
  cat "$tmpdir/non-object.out" >&2
  exit 1
fi

grep -F 'readiness_artifact is invalid: top-level JSON value must be an object' \
  "$tmpdir/non-object.out"

set +e
"$guard" --audit "$complete_audit" --readiness "$missing_checks_json" \
  > "$tmpdir/missing-checks.out"
missing_checks_status=$?
set -e

if [[ "$missing_checks_status" -ne 1 ]]; then
  echo "Expected missing checks readiness artifact to exit 1, got $missing_checks_status" >&2
  cat "$tmpdir/missing-checks.out" >&2
  exit 1
fi

grep -F 'readiness_artifact is invalid: checks must be an array' \
  "$tmpdir/missing-checks.out"

set +e
"$guard" --audit "$complete_audit" --readiness "$invalid_row_schema_json" \
  > "$tmpdir/invalid-row-schema.out"
invalid_row_schema_status=$?
set -e

if [[ "$invalid_row_schema_status" -ne 1 ]]; then
  echo "Expected invalid readiness row schema to exit 1, got $invalid_row_schema_status" >&2
  cat "$tmpdir/invalid-row-schema.out" >&2
  exit 1
fi

grep -F 'readiness_artifact is invalid: checks[0] must be an object' \
  "$tmpdir/invalid-row-schema.out"
grep -F 'readiness_artifact is invalid: checks[1].name must be a non-empty string' \
  "$tmpdir/invalid-row-schema.out"
grep -F 'android_device is invalid: required must be a boolean' \
  "$tmpdir/invalid-row-schema.out"
grep -F 'rooted_physical_device is invalid: message must be a string' \
  "$tmpdir/invalid-row-schema.out"

set +e
"$guard" --audit "$audit_remaining_work" --readiness "$ready_json" \
  > "$tmpdir/audit-remaining-work.out"
audit_remaining_work_status=$?
set -e

if [[ "$audit_remaining_work_status" -ne 1 ]]; then
  echo "Expected covered audit row with remaining work to exit 1, got $audit_remaining_work_status" >&2
  cat "$tmpdir/audit-remaining-work.out" >&2
  exit 1
fi

grep -F "completion audit row is incomplete: Verify remote release gates: remaining evidence must be None before sign-off" \
  "$tmpdir/audit-remaining-work.out"

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
grep -F "completion audit row is incomplete: Remaining environment readiness: result must be Covered locally before sign-off" \
  "$tmpdir/blocked.out"
grep -F 'manual_talkback is blocked: TalkBack inactive' "$tmpdir/blocked.out"
grep -F 'physical_network_handover is manual: Handover needs operator run' "$tmpdir/blocked.out"

set +e
"$guard" --audit "$complete_audit" --readiness "$multiline_message_json" \
  > "$tmpdir/multiline-message.out"
multiline_message_status=$?
set -e

if [[ "$multiline_message_status" -ne 1 ]]; then
  echo "Expected multiline readiness message to exit 1, got $multiline_message_status" >&2
  cat "$tmpdir/multiline-message.out" >&2
  exit 1
fi

grep -F 'production_relay_matrix is manual: Relay matrix config is valid. Config: /tmp/provider-matrix.json Example: test-lab/relay/provider-matrix.example.json' \
  "$tmpdir/multiline-message.out"
if grep -F 'Config: /tmp/provider-matrix.json is' "$tmpdir/multiline-message.out"; then
  echo "Multiline readiness message was split into a bogus sign-off row" >&2
  cat "$tmpdir/multiline-message.out" >&2
  exit 1
fi

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
