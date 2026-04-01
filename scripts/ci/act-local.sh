#!/usr/bin/env bash
#
# Run CI checks locally -- native-first, with act fallback for Linux-only jobs.
#
# Usage:
#   scripts/ci/act-local.sh [--all|--list|--act-only|JOB...]
#
# Examples:
#   scripts/ci/act-local.sh --list                    # Show job matrix
#   scripts/ci/act-local.sh --all                     # Run all feasible checks
#   scripts/ci/act-local.sh rust-lint                  # Run one job
#   scripts/ci/act-local.sh rust-lint cargo-deny       # Run multiple jobs
#   scripts/ci/act-local.sh --act-only rust-lint       # Force act for a job
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORKFLOW="${REPO_ROOT}/.github/workflows/ci.yml"
ACT_DIR="${REPO_ROOT}/.github/act"
WORKSPACE_MANIFEST="$REPO_ROOT/native/rust/Cargo.toml"

# ── Colors ──────────────────────────────────────────────────────────
red()    { printf '\033[0;31m%s\033[0m' "$*"; }
green()  { printf '\033[0;32m%s\033[0m' "$*"; }
yellow() { printf '\033[0;33m%s\033[0m' "$*"; }
bold()   { printf '\033[1m%s\033[0m\n' "$*"; }
info()   { printf '\033[0;36m==>\033[0m %s\n' "$*"; }
pass()   { printf '  \033[0;32mPASS\033[0m %s\n' "$*"; }
fail()   { printf '  \033[0;31mFAIL\033[0m %s\n' "$*"; }
skip()   { printf '  \033[0;33mSKIP\033[0m %s\n' "$*"; }

# ── Job registry ───────────────────────────────────────────────────
# mode: native = run shell commands directly on macOS
#        act   = run via act in Docker (Linux-only)
#        skip  = cannot run locally (emulator, TUN, etc.)
declare -A JOB_MODE=(
  [rust-lint]="native"
  [rust-workspace-tests]="native"
  [rust-cross-check]="native"
  [cargo-deny]="native"
  [rust-loom]="native"
  [rust-turmoil]="native"
  [rust-criterion-bench]="native"
  [rust-network-e2e]="native"
  [build]="native"
  [release-verification]="native"
  [gradle-static-analysis]="native"
  [native-bloat]="native"
  [coverage]="native"
  [cli-packet-smoke]="act"
  [android-macrobenchmark]="skip"
  [android-network-e2e]="skip"
  [linux-tun-e2e]="skip"
  [linux-tun-soak]="skip"
  [rust-native-soak]="skip"
  [rust-native-load]="skip"
  [nightly-rust-coverage]="skip"
)

declare -A JOB_SKIP_REASON=(
  [android-macrobenchmark]="Needs KVM + Android emulator"
  [android-network-e2e]="Needs KVM + Android emulator"
  [linux-tun-e2e]="Needs TUN device + sudo (Linux only)"
  [linux-tun-soak]="Needs TUN device + sudo (Linux only)"
  [rust-native-soak]="Schedule/dispatch-only long-running job"
  [rust-native-load]="Schedule/dispatch-only long-running job"
  [nightly-rust-coverage]="Schedule-only nightly job"
  [cli-packet-smoke]="Needs tcpdump/tshark + cap_net_raw (use --act-only or Linux)"
)

# Ordered by speed -- fast checks first for quick feedback
ALL_NATIVE_JOBS=(
  rust-lint
  cargo-deny
  rust-workspace-tests
  rust-loom
  rust-turmoil
  rust-network-e2e
  rust-criterion-bench
  rust-cross-check
  gradle-static-analysis
  build
  native-bloat
  release-verification
  coverage
)

ALL_ACT_JOBS=(cli-packet-smoke)

# ── Native job runners ─────────────────────────────────────────────

run_native_rust_lint() {
  bash "$REPO_ROOT/scripts/ci/run-rust-lint.sh"
}

run_native_cargo_deny() {
  cargo deny --manifest-path "$WORKSPACE_MANIFEST" check
}

run_native_rust_workspace_tests() {
  bash "$REPO_ROOT/scripts/ci/run-rust-workspace-tests.sh"
}

run_native_rust_loom() {
  cd "$REPO_ROOT/native/rust"
  LOOM_MAX_PREEMPTIONS=3 cargo test --features loom -- loom
}

run_native_rust_turmoil() {
  bash "$REPO_ROOT/scripts/ci/run-rust-turmoil-tests.sh"
}

run_native_rust_network_e2e() {
  bash "$REPO_ROOT/scripts/ci/run-rust-network-e2e.sh"
}

run_native_rust_criterion_bench() {
  cd "$REPO_ROOT/native/rust"
  cargo bench --package ripdpi-bench
}

run_native_rust_cross_check() {
  bash "$REPO_ROOT/scripts/ci/run-rust-cross-check.sh"
}

run_native_gradle_static_analysis() {
  "$REPO_ROOT/gradlew" -p "$REPO_ROOT" staticAnalysis
}

run_native_build() {
  "$REPO_ROOT/gradlew" -p "$REPO_ROOT" assembleDebug testDebugUnitTest
  "$REPO_ROOT/gradlew" -p "$REPO_ROOT" verifyRoborazziDebug
  python3 "$REPO_ROOT/scripts/ci/verify_native_elfs.py"
  python3 "$REPO_ROOT/scripts/ci/verify_native_sizes.py"
}

run_native_native_bloat() {
  python3 "$REPO_ROOT/scripts/ci/verify_native_bloat.py"
}

run_native_release_verification() {
  "$REPO_ROOT/gradlew" -p "$REPO_ROOT" :app:assembleRelease
}

run_native_coverage() {
  "$REPO_ROOT/gradlew" -p "$REPO_ROOT" coverageReport
  bash "$REPO_ROOT/scripts/ci/run-rust-coverage.sh"
}

# ── Dispatcher ─────────────────────────────────────────────────────

run_native_job() {
  local job="$1"
  local fn="run_native_${job//-/_}"
  if declare -f "$fn" &>/dev/null; then
    $fn
  else
    echo "ERROR: no native runner for '$job'" >&2
    return 1
  fi
}

run_act_job() {
  local job="$1"

  if ! command -v act &>/dev/null; then
    echo "ERROR: 'act' not installed (brew install act)" >&2
    return 1
  fi
  if ! docker info &>/dev/null 2>&1; then
    echo "ERROR: Docker not running" >&2
    return 1
  fi

  act \
    -j "$job" \
    -W "$WORKFLOW" \
    -e "${ACT_DIR}/event-push.json" \
    --env "GITHUB_EVENT_NAME=push" \
    --env "RUNNER_TEMP=/tmp/runner-temp"
}

# ── Commands ───────────────────────────────────────────────────────

cmd_list() {
  bold "CI Job Compatibility Matrix (local on macOS)"
  echo ""
  printf "  %-28s %-10s %s\n" "JOB" "MODE" "NOTE"
  printf "  %-28s %-10s %s\n" "---" "----" "----"

  for job in "${ALL_NATIVE_JOBS[@]}"; do
    printf "  %-28s $(green "%-10s") %s\n" "$job" "native" ""
  done
  for job in "${ALL_ACT_JOBS[@]}"; do
    local reason="${JOB_SKIP_REASON[$job]:-}"
    printf "  %-28s $(yellow "%-10s") %s\n" "$job" "act" "$reason"
  done
  for job in android-macrobenchmark android-network-e2e linux-tun-e2e linux-tun-soak rust-native-soak rust-native-load nightly-rust-coverage; do
    local reason="${JOB_SKIP_REASON[$job]:-}"
    printf "  %-28s $(red "%-10s") %s\n" "$job" "skip" "$reason"
  done
  echo ""
  echo "Run:  scripts/ci/act-local.sh --all           (all native + act jobs)"
  echo "      scripts/ci/act-local.sh JOB [JOB...]    (specific jobs)"
  echo "      scripts/ci/act-local.sh --act-only JOB   (force act for a native job)"
}

cmd_run() {
  local force_act=false
  local jobs=()

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --act-only) force_act=true; shift ;;
      *)          jobs+=("$1"); shift ;;
    esac
  done

  if [[ ${#jobs[@]} -eq 0 ]]; then
    echo "ERROR: no jobs specified" >&2
    exit 1
  fi

  local failed=()
  local skipped=()
  local passed=()
  local start_time=$SECONDS

  for job in "${jobs[@]}"; do
    local mode="${JOB_MODE[$job]:-unknown}"

    if [[ "$mode" == "unknown" ]]; then
      fail "$job (unknown job -- use --list to see available jobs)"
      failed+=("$job")
      continue
    fi

    if [[ "$mode" == "skip" ]]; then
      skip "$job -- ${JOB_SKIP_REASON[$job]:-not supported locally}"
      skipped+=("$job")
      continue
    fi

    info "Running: $job (mode: ${force_act:+act}${force_act:-$mode})"
    local job_start=$SECONDS

    if [[ "$force_act" == "true" ]] || [[ "$mode" == "act" ]]; then
      if run_act_job "$job"; then
        local elapsed=$(( SECONDS - job_start ))
        pass "$job (${elapsed}s)"
        passed+=("$job")
      else
        local elapsed=$(( SECONDS - job_start ))
        fail "$job (${elapsed}s)"
        failed+=("$job")
      fi
    else
      if run_native_job "$job"; then
        local elapsed=$(( SECONDS - job_start ))
        pass "$job (${elapsed}s)"
        passed+=("$job")
      else
        local elapsed=$(( SECONDS - job_start ))
        fail "$job (${elapsed}s)"
        failed+=("$job")
      fi
    fi
  done

  # ── Summary ────────────────────────────────────────────────────
  local total_elapsed=$(( SECONDS - start_time ))
  echo ""
  bold "Summary (${total_elapsed}s total)"
  [[ ${#passed[@]}  -gt 0 ]] && echo "  $(green "Passed"):  ${passed[*]}"
  [[ ${#skipped[@]} -gt 0 ]] && echo "  $(yellow "Skipped"): ${skipped[*]}"
  [[ ${#failed[@]}  -gt 0 ]] && echo "  $(red "Failed"):  ${failed[*]}"

  if [[ ${#failed[@]} -gt 0 ]]; then
    exit 1
  fi
}

cmd_all() {
  local all_jobs=("${ALL_NATIVE_JOBS[@]}" "${ALL_ACT_JOBS[@]}")
  cmd_run "${all_jobs[@]}"
}

# ── Pre-flight ─────────────────────────────────────────────────────

check_native_prereqs() {
  local missing=()
  command -v cargo        &>/dev/null || missing+=("cargo (rustup)")
  command -v cargo-nextest &>/dev/null || missing+=("cargo-nextest")
  command -v python3      &>/dev/null || missing+=("python3")

  if [[ ${#missing[@]} -gt 0 ]]; then
    echo "Missing tools: ${missing[*]}" >&2
    echo "Install them before running local CI checks." >&2
    exit 1
  fi
}

# ── Main ───────────────────────────────────────────────────────────

main() {
  local arg="${1:---list}"

  case "$arg" in
    --list|-l)
      cmd_list
      ;;
    --all|-a)
      check_native_prereqs
      cmd_all
      ;;
    --help|-h|help)
      echo "Usage: scripts/ci/act-local.sh [--all|--list|--act-only|JOB...]"
      echo ""
      echo "Options:"
      echo "  --list, -l        Show job compatibility matrix"
      echo "  --all, -a         Run all feasible jobs (native + act)"
      echo "  --act-only JOB    Force running a job via act (Docker)"
      echo "  --help, -h        Show this help"
      echo ""
      echo "Jobs: ${!JOB_MODE[*]}"
      ;;
    --act-only)
      check_native_prereqs
      shift
      cmd_run --act-only "$@"
      ;;
    *)
      check_native_prereqs
      cmd_run "$@"
      ;;
  esac
}

main "$@"
