#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
evidence_path="${RIPDPI_LOCAL_WORKFLOW_EVIDENCE:-}"
head_sha=""
max_age_seconds="${RIPDPI_LOCAL_WORKFLOW_MAX_AGE_SECONDS:-604800}"

required_lanes=(
  ci
  codeql
  local-network-lab
  offline-analytics
  mutation-testing
  fuzz-nightly
)

# shellcheck source=test-lab/scripts/python.sh
source "$repo_root/test-lab/scripts/python.sh"

usage() {
  cat <<USAGE
Usage: $0 --evidence PATH [--head SHA] [--max-age SECONDS]
       $0 --list-required-lanes

Validates local or operator-reviewed workflow evidence for the current commit.
The JSON must name every required release workflow lane, mark it passed, and
bind the evidence to the commit under test.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --evidence)
      evidence_path="${2:?missing --evidence value}"
      shift 2
      ;;
    --head)
      head_sha="${2:?missing --head value}"
      shift 2
      ;;
    --max-age)
      max_age_seconds="${2:?missing --max-age value}"
      shift 2
      ;;
    --list-required-lanes)
      printf '%s\n' "${required_lanes[@]}"
      exit 0
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

if [[ -z "$evidence_path" ]]; then
  echo "Missing local workflow evidence. Set RIPDPI_LOCAL_WORKFLOW_EVIDENCE or pass --evidence." >&2
  exit 2
fi
if [[ ! -f "$evidence_path" ]]; then
  echo "Local workflow evidence does not exist: $evidence_path" >&2
  exit 2
fi
if [[ -z "$head_sha" ]]; then
  head_sha="$(git -C "$repo_root" rev-parse HEAD)"
fi

python_bin="$(ripdpi_resolve_python "local workflow evidence validation")"
"$python_bin" - "$evidence_path" "$head_sha" "$max_age_seconds" "$(date +%s)" "${required_lanes[@]}" <<'PY'
import json
import sys


def fail(message):
    raise SystemExit(message)


evidence_path = sys.argv[1]
head_sha = sys.argv[2]
try:
    max_age_seconds = int(sys.argv[3])
    current_epoch = int(sys.argv[4])
except ValueError as exc:
    fail(f"invalid freshness configuration: {exc}")
required_lanes = set(sys.argv[5:])

try:
    with open(evidence_path, encoding="utf-8") as handle:
        data = json.load(handle)
except Exception as exc:
    fail(f"could not parse local workflow evidence JSON: {exc}")

if not isinstance(data, dict):
    fail("local workflow evidence must be a JSON object")
if data.get("version") != 1:
    fail("local workflow evidence version must be 1")
commit = data.get("commit")
if commit != head_sha:
    fail(f"local workflow evidence commit {commit!r} does not match HEAD {head_sha!r}")
generated_at = data.get("generatedAtEpoch")
if not isinstance(generated_at, int):
    fail("local workflow evidence generatedAtEpoch must be an integer")
if generated_at > current_epoch + 300:
    fail(f"local workflow evidence generatedAtEpoch is in the future: {generated_at}")
age_seconds = current_epoch - generated_at
if age_seconds > max_age_seconds:
    fail(
        "local workflow evidence is stale: "
        f"generatedAtEpoch is {age_seconds}s old; regenerate within {max_age_seconds}s"
    )

checks = data.get("checks")
if not isinstance(checks, list):
    fail("local workflow evidence checks must be an array")

seen = {}
allowed_sources = {"act", "local", "github", "vm", "emulator", "manual"}
for index, check in enumerate(checks):
    if not isinstance(check, dict):
        fail(f"checks[{index}] must be an object")
    name = check.get("name")
    status = check.get("status")
    source = check.get("source")
    evidence = check.get("evidence")
    command = check.get("command")
    if not isinstance(name, str) or not name:
        fail(f"checks[{index}].name must be a non-empty string")
    if name in seen:
        fail(f"duplicate local workflow evidence lane: {name}")
    if status != "passed":
        fail(f"{name} status must be passed, got {status!r}")
    if source not in allowed_sources:
        fail(f"{name} source must be one of {sorted(allowed_sources)}, got {source!r}")
    if not isinstance(evidence, str) or not evidence.strip():
        fail(f"{name} evidence must be a non-empty string")
    if command is not None and not isinstance(command, str):
        fail(f"{name} command must be a string when present")
    seen[name] = check

missing = sorted(required_lanes.difference(seen))
if missing:
    fail("Missing local workflow evidence lanes: " + ", ".join(missing))

unexpected = sorted(set(seen).difference(required_lanes))
if unexpected:
    fail("Unexpected local workflow evidence lanes: " + ", ".join(unexpected))

sources = ", ".join(f"{name}:{seen[name]['source']}" for name in sorted(seen))
print(f"Local workflow evidence valid for {head_sha[:12]} with lanes {sources}.")
PY
