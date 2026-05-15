#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-checklist-coverage-test.XXXXXX")"
trap 'rm -rf "$tmpdir"' EXIT

checklist="$tmpdir/checklist.md"
evidence="$tmpdir/evidence.md"
output="$tmpdir/output.txt"

write_checklist() {
  cat >"$checklist" <<'MD'
# Fixture Checklist

## Alpha

- [ ] Alpha item.

## Beta

- [ ] Beta item.
MD
}

write_evidence() {
  cat >"$evidence" <<'MD'
# Fixture Evidence

| Checklist section | Status | Evidence | Remaining work |
| --- | --- | --- | --- |
| Alpha | Covered locally | Unit fixture | None |
| Beta | Partial | Device fixture | Manual run |
MD
}

expect_failure() {
  local expected="$1"
  set +e
  "$repo_root/test-lab/scripts/test-feature-checklist-coverage.sh" \
    "$checklist" \
    "$evidence" >"$output" 2>&1
  local status=$?
  set -e
  cat "$output"
  if [[ "$status" -eq 0 ]]; then
    echo "Expected checklist coverage fixture to fail: $expected" >&2
    exit 1
  fi
  grep -F "$expected" "$output"
}

write_checklist
write_evidence
"$repo_root/test-lab/scripts/test-feature-checklist-coverage.sh" \
  "$checklist" \
  "$evidence" >/dev/null

write_checklist
write_evidence
perl -0pi -e 's/\| Beta \| Partial \| Device fixture \| Manual run \|\n//' "$evidence"
expect_failure "Missing evidence row for checklist section: Beta"

write_checklist
write_evidence
printf '| Alpha | Partial | Duplicate fixture | Manual run |\n' >>"$evidence"
expect_failure "Duplicate evidence row for checklist section: Alpha"

write_checklist
write_evidence
printf '| Gamma | Partial | Extra fixture | Manual run |\n' >>"$evidence"
expect_failure "Evidence row does not match a checklist section: Gamma"

write_checklist
write_evidence
perl -0pi -e 's/\| Beta \| Partial \| Device fixture \| Manual run \|/\| Beta \| Partial \| Device fixture \| None \|/' "$evidence"
expect_failure "Invalid checklist evidence row: Beta: Partial row must name remaining work"

write_checklist
write_evidence
perl -0pi -e 's/\| Alpha \| Covered locally \| Unit fixture \| None \|/\| Alpha \| Covered locally \| Unit fixture \| Manual run \|/' "$evidence"
expect_failure "Invalid checklist evidence row: Alpha: Covered locally row must start remaining work with 'None'"

echo "Feature checklist coverage fixture self-test passed."
