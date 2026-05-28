#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=test-lab/scripts/python.sh
source "$repo_root/test-lab/scripts/python.sh"
python_bin="$(ripdpi_resolve_python "local workflow evidence tests")"
tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-local-workflow-evidence-test.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

head_sha="$(git -C "$repo_root" rev-parse HEAD)"
valid_json="$tmpdir/local-workflow-valid.json"
missing_json="$tmpdir/local-workflow-missing.json"
wrong_commit_json="$tmpdir/local-workflow-wrong-commit.json"
readiness_json="$tmpdir/feature-gap-readiness-local-workflow.json"

write_evidence() {
  local output="$1"
  local mutation="${2:-valid}"
  "$python_bin" - "$output" "$head_sha" "$mutation" <<'PY'
import json
import sys
import time

output, head_sha, mutation = sys.argv[1:4]
checks = [
    {"name": "ci", "status": "passed", "source": "act", "command": "scripts/ci/act-local.sh build native-bloat release-verification coverage rust-cross-check rust-lint", "evidence": "operator-current-evidence.md"},
    {"name": "codeql", "status": "passed", "source": "github", "evidence": "operator-current-evidence.md"},
    {"name": "local-network-lab", "status": "passed", "source": "act", "command": "scripts/ci/act-local.sh local-network-lab", "evidence": "operator-current-evidence.md"},
    {"name": "offline-analytics", "status": "passed", "source": "local", "command": "python3 -m unittest scripts.tests.test_offline_analytics_pipeline && python3 -m scripts.analytics.pipeline run-all --manifest scripts/analytics/sample-corpus.json", "evidence": "operator-current-evidence.md"},
    {"name": "mutation-testing", "status": "passed", "source": "act", "command": "act workflow_dispatch -j mutants -W .github/workflows/mutation-testing.yml", "evidence": "operator-current-evidence.md"},
    {"name": "fuzz-nightly", "status": "passed", "source": "local", "command": "RIPDPI_FUZZ_SECONDS=30 bash scripts/ci/run-rust-fuzz-smoke.sh", "evidence": "operator-current-evidence.md"},
]
commit = head_sha
if mutation == "missing":
    checks = [check for check in checks if check["name"] != "fuzz-nightly"]
elif mutation == "wrong_commit":
    commit = "0" * 40
elif mutation != "valid":
    raise SystemExit(f"unknown mutation: {mutation}")

with open(output, "w", encoding="utf-8") as handle:
    json.dump({"version": 1, "commit": commit, "generatedAtEpoch": int(time.time()), "checks": checks}, handle)
PY
}

expect_failure() {
  local evidence="$1"
  local expected="$2"
  set +e
  "$repo_root/test-lab/scripts/check-local-workflow-evidence.sh" \
    --evidence "$evidence" \
    --head "$head_sha" \
    > "$tmpdir/failure.out" 2>&1
  local status=$?
  set -e
  if [[ "$status" -eq 0 ]]; then
    echo "Expected local workflow evidence validation to fail" >&2
    cat "$tmpdir/failure.out" >&2
    exit 1
  fi
  grep -F "$expected" "$tmpdir/failure.out"
}

write_evidence "$valid_json"
"$repo_root/test-lab/scripts/check-local-workflow-evidence.sh" \
  --evidence "$valid_json" \
  --head "$head_sha" \
  > "$tmpdir/valid.out"
grep -F "Local workflow evidence valid for ${head_sha:0:12}" "$tmpdir/valid.out"

write_evidence "$missing_json" missing
expect_failure "$missing_json" "Missing local workflow evidence lanes: fuzz-nightly"

write_evidence "$wrong_commit_json" wrong_commit
expect_failure "$wrong_commit_json" "does not match HEAD"

RIPDPI_LOCAL_WORKFLOW_EVIDENCE="$valid_json" \
  RIPDPI_IGNORE_DIRTY_WORKTREE_FOR_READINESS=true \
  RIPDPI_REMOTE_COMPARE_REF=origin/ripdpi-missing-test-ref \
  "$repo_root/test-lab/scripts/check-feature-gap-readiness.sh" \
  --output "$readiness_json" >/dev/null

"$python_bin" - "$readiness_json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    data = json.load(handle)
checks = {check["name"]: check for check in data.get("checks", [])}
remote = checks.get("remote_workflow_confirmation")
if remote is None:
    raise SystemExit("missing remote_workflow_confirmation readiness check")
if remote.get("status") != "ready":
    raise SystemExit(f"expected local workflow evidence to mark remote workflow row ready, got {remote!r}")
message = remote.get("message", "")
if "Local workflow evidence accepted for the current commit" not in message:
    raise SystemExit(f"local workflow readiness message is unclear: {message!r}")
PY

echo "Local workflow evidence self-test passed."
