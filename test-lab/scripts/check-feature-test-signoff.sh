#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
audit_path="$repo_root/docs/feature-test-completion-audit-2026-05-14.md"
readiness_path=""
keep_readiness=false
list_required_readiness=false
list_required_audit_rows=false
required_audit_rows=(
  "Use docs/feature-test-checklist.md as the source checklist"
  "Fix all issues found during the local pass"
  "Verify Appium installation and current app flows"
  "Verify Maestro installation and current smoke flows"
  "Verify static local quality gates for the current head"
  "Verify local artifacts referenced by the evidence ledger exist"
  "Verify remaining environment readiness"
  "Verify rooted behavior"
  "Verify physical network matrix"
  "Verify relay provider matrix"
  "Verify accessibility with TalkBack"
  "Verify routed VM packet-loss lab"
  "Verify remote release gates"
)
required_readiness_checks=(
  android_device
  rooted_physical_device
  manual_talkback
  physical_network_handover
  routed_netem_vm
  production_relay_matrix
  remote_workflow_confirmation
)

usage() {
  cat <<USAGE
Usage: $0 [--audit PATH] [--readiness PATH] [--list-required-readiness] [--list-required-audit-rows]

Read-only pre-signoff guard for docs/feature-test-checklist.md.

The check fails while the completion audit is not complete or while the
readiness preflight still reports blocked/manual rows. It does not replace the
manual evidence template; it only prevents treating runbooks or partial local
automation as full application-test sign-off.

Required readiness rows:
- android_device
- rooted_physical_device
- manual_talkback
- physical_network_handover
- routed_netem_vm
- production_relay_matrix
- remote_workflow_confirmation

Required completion-audit rows:
$(printf -- '- %s\n' "${required_audit_rows[@]}")
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --list-required-readiness)
      list_required_readiness=true
      shift
      ;;
    --list-required-audit-rows)
      list_required_audit_rows=true
      shift
      ;;
    --audit)
      audit_path="${2:?missing --audit value}"
      shift 2
      ;;
    --readiness)
      readiness_path="${2:?missing --readiness value}"
      keep_readiness=true
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

if [[ "$list_required_readiness" == "true" ]]; then
  printf '%s\n' "${required_readiness_checks[@]}"
  exit 0
fi

if [[ "$list_required_audit_rows" == "true" ]]; then
  printf '%s\n' "${required_audit_rows[@]}"
  exit 0
fi

if [[ ! -f "$audit_path" ]]; then
  echo "Completion audit not found: $audit_path" >&2
  exit 2
fi

if [[ -z "$readiness_path" ]]; then
  readiness_path="$(mktemp "${TMPDIR:-/tmp}/ripdpi-feature-readiness.XXXXXX.json")"
  trap 'rm -f "$readiness_path"' EXIT
  "$repo_root/test-lab/scripts/check-feature-gap-readiness.sh" \
    --output "$readiness_path" >/dev/null
elif [[ ! -f "$readiness_path" ]]; then
  echo "Readiness artifact not found: $readiness_path" >&2
  exit 2
fi

failures=()

if ! grep -Fq 'Status: **complete**.' "$audit_path"; then
  failures+=("completion audit is not marked complete")
fi

if grep -Fq 'Status: **not complete**.' "$audit_path"; then
  failures+=("completion audit explicitly says not complete")
fi

if grep -Fq '| Partial |' "$audit_path"; then
  failures+=("completion audit still contains Partial rows")
fi

while IFS= read -r row; do
  failures+=("completion audit is missing required row: $row")
done < <(
  python3 - "$audit_path" "${required_audit_rows[@]}" <<'PY'
import re
import sys
from pathlib import Path


def normalize(value):
    value = value.lower().replace("`", "")
    value = re.sub(r"[^a-z0-9]+", " ", value)
    return re.sub(r"\s+", " ", value).strip()


audit = Path(sys.argv[1]).read_text(encoding="utf-8")
required_rows = sys.argv[2:]
documented_rows = set()
for line in audit.splitlines():
    if not line.startswith("|"):
        continue
    cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
    if len(cells) != 4 or cells[0] in {"Requirement", "---"}:
        continue
    documented_rows.add(normalize(cells[0]))

for row in required_rows:
    if normalize(row) not in documented_rows:
        print(row)
PY
)

while IFS=$'\t' read -r name status message; do
  if [[ "$status" != "ready" ]]; then
    failures+=("$name is $status: $message")
  fi
done < <(
  python3 - "$readiness_path" "${required_readiness_checks[@]}" <<'PY'
import json
import sys

try:
    with open(sys.argv[1], encoding="utf-8") as handle:
        data = json.load(handle)
except Exception as exc:
    print(
        "readiness_artifact",
        "invalid",
        f"could not parse readiness JSON: {exc}",
        sep="\t",
    )
    raise SystemExit(0)

if not isinstance(data, dict):
    print("readiness_artifact", "invalid", "top-level JSON value must be an object", sep="\t")
    raise SystemExit(0)

checks = data.get("checks")
if not isinstance(checks, list):
    print("readiness_artifact", "invalid", "checks must be an array", sep="\t")
    raise SystemExit(0)

required_names = set(sys.argv[2:])
seen_required = set()
allowed_statuses = {"ready", "manual", "blocked"}
for index, check in enumerate(checks):
    if not isinstance(check, dict):
        print(
            "readiness_artifact",
            "invalid",
            f"checks[{index}] must be an object",
            sep="\t",
        )
        continue
    name = check.get("name", "unknown")
    status = check.get("status", "unknown")
    message = check.get("message", "")
    if not isinstance(name, str) or not name:
        print("readiness_artifact", "invalid", f"checks[{index}].name must be a non-empty string", sep="\t")
        continue
    if status not in allowed_statuses:
        print(name, "invalid", f"status must be one of {sorted(allowed_statuses)}", sep="\t")
        continue
    if check.get("required") is not True and check.get("required") is not False:
        print(name, "invalid", "required must be a boolean", sep="\t")
        continue
    if not isinstance(message, str):
        print(name, "invalid", "message must be a string", sep="\t")
        continue
    if check.get("required") is True:
        if name in seen_required:
            print(name, "invalid", "duplicate required readiness check", sep="\t")
            continue
        seen_required.add(name)
        print(
            name,
            status,
            message,
            sep="\t",
        )

for name in sorted(required_names - seen_required):
    print(
        name,
        "missing",
        "required readiness check is absent from the artifact",
        sep="\t",
    )
PY
)

if [[ "${#failures[@]}" -gt 0 ]]; then
  echo "Feature test sign-off blocked:"
  for failure in "${failures[@]}"; do
    printf -- '- %s\n' "$failure"
  done
  if [[ "$keep_readiness" == "true" ]]; then
    echo "Readiness artifact: $readiness_path"
  fi
  exit 1
fi

echo "Feature test sign-off guard passed."
