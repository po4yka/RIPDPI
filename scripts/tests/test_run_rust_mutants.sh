#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-mutants-wrapper-test.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

bin_dir="$tmp_dir/bin"
log_path="$tmp_dir/cargo.log"
git_log_path="$tmp_dir/git.log"
err_path="$tmp_dir/stderr.log"
mkdir -p "$bin_dir"

cat >"$bin_dir/cargo" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
  metadata)
    printf '{"packages":[{"name":"ripdpi-desync"},{"name":"ripdpi-packets"}]}\n'
    ;;
  mutants)
    printf 'RIPDPI_REPO_ROOT=%q\n' "${RIPDPI_REPO_ROOT:-}" >>"$CARGO_STUB_LOG"
    printf 'RIPDPI_CONTRACT_FIXTURES_DIR=%q\n' "${RIPDPI_CONTRACT_FIXTURES_DIR:-}" >>"$CARGO_STUB_LOG"
    printf '%q ' "$@" >>"$CARGO_STUB_LOG"
    printf '\n' >>"$CARGO_STUB_LOG"
    ;;
  *)
    echo "unexpected cargo command: ${1:-}" >&2
    exit 9
    ;;
esac
STUB
chmod +x "$bin_dir/cargo"

cat >"$bin_dir/git" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail

if [ "${1:-}" = "diff" ]; then
  printf '%q ' "$@" >>"$GIT_STUB_LOG"
  printf '\n' >>"$GIT_STUB_LOG"
  cat <<'DIFF'
diff --git a/crates/ripdpi-desync/src/lib.rs b/crates/ripdpi-desync/src/lib.rs
--- a/crates/ripdpi-desync/src/lib.rs
+++ b/crates/ripdpi-desync/src/lib.rs
@@ -1 +1 @@
-pub fn sample() -> bool { false }
+pub fn sample() -> bool { true }
DIFF
else
  echo "unexpected git command: ${1:-}" >&2
  exit 9
fi
STUB
chmod +x "$bin_dir/git"

run_wrapper() {
  PATH="$bin_dir:$PATH" CARGO_STUB_LOG="$log_path" GIT_STUB_LOG="$git_log_path" bash "$repo_root/scripts/ci/run-rust-mutants.sh" "$@"
}

assert_log_contains() {
  local expected="$1"
  if ! grep -Fq -- "$expected" "$log_path"; then
    echo "expected cargo log to contain: $expected" >&2
    echo "actual cargo log:" >&2
    cat "$log_path" >&2
    exit 1
  fi
}

assert_log_not_contains() {
  local unexpected="$1"
  if grep -Fq -- "$unexpected" "$log_path"; then
    echo "expected cargo log not to contain: $unexpected" >&2
    echo "actual cargo log:" >&2
    cat "$log_path" >&2
    exit 1
  fi
}

: >"$log_path"
: >"$git_log_path"
missing_parent_output="$tmp_dir/missing-parent/mutants-output"
MUTANTS_OUTPUT_DIR="$missing_parent_output" run_wrapper --in-diff "git diff origin/main...HEAD"
test -d "$(dirname "$missing_parent_output")"
assert_log_contains "mutants --manifest-path"
assert_log_contains "--test-tool nextest"
assert_log_contains "--output"
assert_log_contains "--output $missing_parent_output"
assert_log_contains "--in-diff"
assert_log_not_contains "git\\ diff\\ origin/main...HEAD"
grep -Fq "diff --relative=native/rust origin/main...HEAD" "$git_log_path"
assert_log_contains "RIPDPI_REPO_ROOT=$repo_root"
assert_log_contains "RIPDPI_CONTRACT_FIXTURES_DIR=$repo_root/contract-fixtures"
assert_log_not_contains "--jobs"

: >"$log_path"
MUTANTS_PACKAGES="ripdpi-desync missing-package" MUTANTS_JOBS=4 run_wrapper
assert_log_contains "--jobs 4"
assert_log_contains "--package ripdpi-desync"
assert_log_not_contains "--package missing-package"

: >"$log_path"
if MUTANTS_JOBS=auto run_wrapper 2>"$err_path"; then
  echo "expected non-numeric MUTANTS_JOBS to fail" >&2
  exit 1
fi
grep -Fq "MUTANTS_JOBS must be a numeric cargo-mutants --jobs value" "$err_path"
if [ -s "$log_path" ]; then
  echo "expected invalid MUTANTS_JOBS to fail before invoking cargo" >&2
  cat "$log_path" >&2
  exit 1
fi

echo "run-rust-mutants wrapper self-test passed."
