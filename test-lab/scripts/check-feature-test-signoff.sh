#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
audit_path="$repo_root/docs/feature-test-completion-audit-2026-05-14.md"
readiness_path=""
keep_readiness=false

usage() {
  cat <<USAGE
Usage: $0 [--audit PATH] [--readiness PATH]

Read-only pre-signoff guard for docs/feature-test-checklist.md.

The check fails while the completion audit is not complete or while the
readiness preflight still reports blocked/manual rows. It does not replace the
manual evidence template; it only prevents treating runbooks or partial local
automation as full application-test sign-off.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
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

while IFS=$'\t' read -r name status message; do
  if [[ "$status" != "ready" ]]; then
    failures+=("$name is $status: $message")
  fi
done < <(
  python3 - "$readiness_path" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)

for check in data.get("checks", []):
    if check.get("required") is True:
        print(
            check.get("name", "unknown"),
            check.get("status", "unknown"),
            check.get("message", ""),
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
