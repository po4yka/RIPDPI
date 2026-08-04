#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="${1:-$lab_root/artifacts}"
retention_class="public-sanitized"

usage() {
  cat <<USAGE
Usage: $0 [--retention-class CLASS] [PATH]

Scans a lab artifact directory or tar.gz archive for secret-looking keys that
must not appear in exported diagnostic handoff material.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --retention-class) retention_class="${2:?missing retention class}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "Unknown argument: $1" >&2; exit 2 ;;
    *) target="$1"; shift ;;
  esac
done

if [[ -f "$target" ]]; then
  case "$target" in
    *.tar.gz|*.tgz) ;;
    *)
      echo "Unsupported artifact file type: $target" >&2
      exit 2
      ;;
  esac
elif [[ -d "$target" ]]; then
  python3 "$lab_root/../scripts/ci/evidence_retention.py" \
    --policy "$lab_root/../quality/evidence-retention.json" \
    check-directory \
    --retention-class "$retention_class" \
    "$target"
else
  echo "Path not found: $target" >&2
  exit 2
fi

if [[ -f "$target" ]]; then
  python3 "$lab_root/../scripts/ci/evidence_retention.py" \
    --policy "$lab_root/../quality/evidence-retention.json" \
    check-archive \
    --retention-class "$retention_class" \
    "$target"
fi

echo "Artifact redaction check passed: $target"
