#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
checker="$repo_root/test-lab/scripts/test-feature-artifact-paths.sh"
tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-artifact-paths-test.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

valid_evidence="$tmpdir/valid-evidence.md"
embedded_evidence="$tmpdir/embedded-evidence.md"
missing_evidence="$tmpdir/missing-evidence.md"
fixture_dir="$tmpdir/fixture"

mkdir -p "$fixture_dir/test-lab/artifacts"
printf '{"status":"ok"}\n' > "$fixture_dir/test-lab/artifacts/doctor.json"

cat > "$valid_evidence" <<'EOF'
# Fixture Evidence

| Checklist area | Command or artifact | Result |
| --- | --- | --- |
| Test-lab doctor | `test-lab/artifacts/doctor.json` | Pass |
EOF

cat > "$embedded_evidence" <<'EOF'
# Fixture Evidence

| Checklist area | Command or artifact | Result |
| --- | --- | --- |
| Embedded command artifact | `./tool --out-dir test-lab/artifacts/doctor.json` | Pass |
EOF

cat > "$missing_evidence" <<'EOF'
# Fixture Evidence

| Checklist area | Command or artifact | Result |
| --- | --- | --- |
| Missing artifact | `test-lab/artifacts/fixture-missing-artifact.json` | Should fail |
EOF

RIPDPI_ARTIFACT_ROOT="$fixture_dir" "$checker" "$valid_evidence" \
  | grep -F 'Feature artifact path self-test passed: 1 referenced artifact paths, 0 missing.'
RIPDPI_ARTIFACT_ROOT="$fixture_dir" "$checker" "$embedded_evidence" \
  | grep -F 'Feature artifact path self-test passed: 1 referenced artifact paths, 0 missing.'

set +e
RIPDPI_ARTIFACT_ROOT="$fixture_dir" "$checker" "$missing_evidence" \
  > "$tmpdir/missing.out" 2>&1
missing_status=$?
set -e

if [[ "$missing_status" -ne 1 ]]; then
  echo "Expected missing artifact fixture to exit 1, got $missing_status" >&2
  cat "$tmpdir/missing.out" >&2
  exit 1
fi

grep -F 'Missing referenced test-lab artifact: test-lab/artifacts/fixture-missing-artifact.json' \
  "$tmpdir/missing.out"

echo "Feature artifact path fixture self-test passed."
