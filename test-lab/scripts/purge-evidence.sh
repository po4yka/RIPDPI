#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
root="$repo_root/test-lab/artifacts"
execute=false

usage() {
  cat <<USAGE
Usage: $0 [--root PATH] [--execute]

Lists expired policy-managed evidence by default. --execute removes only an
expired artifact and its validated *.retention.json sidecar under a configured
repository purge root.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --root) root="${2:?missing --root value}"; shift 2 ;;
    --execute) execute=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

root="$(cd "$root" 2>/dev/null && pwd -P)" || {
  echo "Evidence purge root does not exist" >&2
  exit 2
}
allowed=false
for relative in test-lab/artifacts build/release-candidate-downloads; do
  candidate="$repo_root/$relative"
  if [[ -d "$candidate" && "$(cd "$candidate" && pwd -P)" == "$root" ]]; then
    allowed=true
  fi
done
[[ "$allowed" == "true" ]] || {
  echo "Refusing purge outside configured repository evidence roots: $root" >&2
  exit 2
}

args=(--policy "$repo_root/quality/evidence-retention.json" purge "$root")
if [[ "$execute" != "true" ]]; then
  args+=(--dry-run)
fi
python3 "$repo_root/scripts/ci/evidence_retention.py" "${args[@]}"
