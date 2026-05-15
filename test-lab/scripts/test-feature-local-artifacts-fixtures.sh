#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
checker="$repo_root/test-lab/scripts/test-feature-local-artifacts.sh"
tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-local-artifacts-test.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

valid_evidence="$tmpdir/valid-evidence.md"
missing_evidence="$tmpdir/missing-evidence.md"
empty_evidence="$tmpdir/empty-evidence.md"
fixture_dir="$tmpdir/fixture"

mkdir -p "$fixture_dir/app/build/outputs/apk/github/debug"
printf 'apk\n' > "$fixture_dir/app/build/outputs/apk/github/debug/app-fixture-debug.apk"
mkdir -p "$fixture_dir/app/build/outputs/androidTest-results/connected/debug"
printf '<testsuite />\n' \
  > "$fixture_dir/app/build/outputs/androidTest-results/connected/debug/TEST-Pixel Fixture - 16-_app-.xml"
mkdir -p "$fixture_dir/core/service/build/test-results/testDebugUnitTest"
: > "$fixture_dir/core/service/build/test-results/testDebugUnitTest/TEST-empty.xml"

cat > "$valid_evidence" <<'EOF'
# Fixture Evidence

| Checklist area | Command or artifact | Result |
| --- | --- | --- |
| Debug APK | `$PWD/app/build/outputs/apk/github/debug/app-fixture-debug.apk` | Pass |
| Connected XML | `app/build/outputs/androidTest-results/connected/debug/TEST-Pixel Fixture - 16-_app-.xml` | Pass |
EOF

cat > "$missing_evidence" <<'EOF'
# Fixture Evidence

| Checklist area | Command or artifact | Result |
| --- | --- | --- |
| Missing APK | `app/build/outputs/apk/github/debug/app-missing-debug.apk` | Should fail |
EOF

cat > "$empty_evidence" <<'EOF'
# Fixture Evidence

| Checklist area | Command or artifact | Result |
| --- | --- | --- |
| Empty XML | `core/service/build/test-results/testDebugUnitTest/TEST-empty.xml` | Should fail |
EOF

RIPDPI_ARTIFACT_ROOT="$fixture_dir" "$checker" "$valid_evidence" \
  | grep -F 'Feature local artifact self-test passed: 2 referenced local artifacts, 0 missing, 0 empty.'

set +e
RIPDPI_ARTIFACT_ROOT="$fixture_dir" "$checker" "$missing_evidence" \
  > "$tmpdir/missing.out" 2>&1
missing_status=$?
set -e

if [[ "$missing_status" -ne 1 ]]; then
  echo "Expected missing local artifact fixture to exit 1, got $missing_status" >&2
  cat "$tmpdir/missing.out" >&2
  exit 1
fi

grep -F 'Missing referenced local artifact: app/build/outputs/apk/github/debug/app-missing-debug.apk' \
  "$tmpdir/missing.out"

set +e
RIPDPI_ARTIFACT_ROOT="$fixture_dir" "$checker" "$empty_evidence" \
  > "$tmpdir/empty.out" 2>&1
empty_status=$?
set -e

if [[ "$empty_status" -ne 1 ]]; then
  echo "Expected empty local artifact fixture to exit 1, got $empty_status" >&2
  cat "$tmpdir/empty.out" >&2
  exit 1
fi

grep -F 'Empty referenced local artifact: core/service/build/test-results/testDebugUnitTest/TEST-empty.xml' \
  "$tmpdir/empty.out"

echo "Feature local artifact fixture self-test passed."
