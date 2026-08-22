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
DEFAULT_WORKFLOW="${REPO_ROOT}/.github/workflows/ci.yml"
ACT_DIR="${REPO_ROOT}/.github/act"
WORKSPACE_MANIFEST="$REPO_ROOT/native/rust/Cargo.toml"
ACT_UBUNTU_IMAGE="${ACT_UBUNTU_IMAGE:-catthehacker/ubuntu:act-latest}"
case "$(uname -m)" in
  arm64|aarch64) default_act_container_arch="linux/arm64" ;;
  *) default_act_container_arch="linux/amd64" ;;
esac
ACT_CONTAINER_ARCH="${ACT_CONTAINER_ARCH:-$default_act_container_arch}"
ACT_PACKET_CAPTURE_CAPS="${ACT_PACKET_CAPTURE_CAPS:-true}"
ACT_CONTAINER_OPTIONS="${ACT_CONTAINER_OPTIONS:-}"

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
  [rust-fuzz-smoke]="native"
  [l7-dryrun]="native"
  [rust-criterion-bench]="native"
  [rust-network-e2e]="native"
  [build]="native"
  [release-verification]="native"
  [gradle-static-analysis]="native"
  [native-bloat]="native"
  [kotlin-coverage]="native"
  [rust-coverage]="native"
  [cli-packet-smoke]="act"
  [local-network-lab]="act"
  [l7-live]="act"
  [android-macrobenchmark]="skip"
  [android-network-e2e]="skip"
  [linux-tun-e2e]="skip"
  [linux-tun-soak]="skip"
  [rust-native-soak]="skip"
  [rust-native-load]="skip"
  [nightly-kotlin-coverage]="skip"
  [nightly-rust-coverage]="skip"
)

declare -A JOB_SKIP_REASON=(
  [android-macrobenchmark]="Needs KVM + Android emulator"
  [android-network-e2e]="Needs KVM + Android emulator"
  [linux-tun-e2e]="Needs TUN device + sudo (Linux only)"
  [linux-tun-soak]="Needs TUN device + sudo (Linux only)"
  [rust-native-soak]="Schedule/dispatch-only long-running job"
  [rust-native-load]="Schedule/dispatch-only long-running job"
  [nightly-kotlin-coverage]="Schedule/dispatch-only nightly job"
  [nightly-rust-coverage]="Schedule-only nightly job"
  [cli-packet-smoke]="Needs tcpdump/tshark + cap_net_raw (use --act-only or Linux)"
  [local-network-lab]="Runs Docker lab doctor and readiness preflights through act"
  [l7-live]="Runs Linux nfqueue/nftables L7 adversarial live smoke through act"
)

declare -A JOB_WORKFLOW=(
  [cli-packet-smoke]="${REPO_ROOT}/.github/workflows/ci.yml"
  [local-network-lab]="${REPO_ROOT}/.github/workflows/local-network-lab.yml"
  [l7-live]="${REPO_ROOT}/.github/workflows/l7-adversarial-live.yml"
)

declare -A JOB_ACT_ID=(
  [local-network-lab]="lab-doctor"
  [l7-live]="smoke"
)

# Ordered by speed -- fast checks first for quick feedback
ALL_NATIVE_JOBS=(
  rust-lint
  cargo-deny
  rust-workspace-tests
  rust-loom
  rust-turmoil
  rust-fuzz-smoke
  l7-dryrun
  rust-network-e2e
  rust-criterion-bench
  rust-cross-check
  gradle-static-analysis
  build
  native-bloat
  release-verification
  kotlin-coverage
  rust-coverage
)

ALL_ACT_JOBS=(cli-packet-smoke local-network-lab l7-live)

# ── Helpers ─────────────────────────────────────────────────────────

require_cmd() {
  local cmd="$1" install_hint="$2"
  if ! command -v "$cmd" &>/dev/null; then
    echo "ERROR: '$cmd' not installed. Install with: $install_hint" >&2
    return 1
  fi
}

ensure_docker_desktop_credential_helper() {
  local docker_desktop_bin="/Applications/Docker.app/Contents/Resources/bin"
  if ! command -v docker-credential-desktop &>/dev/null && [[ -x "$docker_desktop_bin/docker-credential-desktop" ]]; then
    export PATH="$docker_desktop_bin:$PATH"
  fi
}

# ── Native job runners ─────────────────────────────────────────────

run_native_rust_lint() {
  bash "$REPO_ROOT/scripts/ci/run-rust-lint.sh"
}

run_native_cargo_deny() {
  require_cmd cargo-deny "cargo install cargo-deny" || return
  bash "$REPO_ROOT/scripts/ci/cargo-guarded.sh" cargo deny --locked --manifest-path "$WORKSPACE_MANIFEST" check
}

run_native_rust_workspace_tests() {
  bash "$REPO_ROOT/scripts/ci/run-rust-workspace-tests.sh"
}

run_native_rust_loom() {
  (
    cd "$REPO_ROOT/native/rust" &&
      LOOM_MAX_PREEMPTIONS=3 cargo test --locked --features loom -- loom
  )
}

run_native_rust_turmoil() {
  bash "$REPO_ROOT/scripts/ci/run-rust-turmoil-tests.sh"
}

run_native_rust_fuzz_smoke() {
  bash "$REPO_ROOT/scripts/ci/run-rust-fuzz-smoke.sh"
}

run_native_l7_dryrun() {
  bash "$REPO_ROOT/scripts/ci/run-l7-adversarial-dryrun.sh"
}

run_native_rust_network_e2e() {
  bash "$REPO_ROOT/scripts/ci/run-rust-network-e2e.sh"
}

run_native_rust_criterion_bench() {
  (
    cd "$REPO_ROOT/native/rust" &&
      cargo bench --locked --package ripdpi-bench
  )
}

run_native_rust_cross_check() {
  bash "$REPO_ROOT/scripts/ci/run-rust-cross-check.sh"
}

run_native_gradle_static_analysis() {
  "$REPO_ROOT/gradlew" -p "$REPO_ROOT" staticAnalysis
}

run_native_build() {
  "$REPO_ROOT/gradlew" -p "$REPO_ROOT" assembleDebug testDebugUnitTest || return
  "$REPO_ROOT/gradlew" -p "$REPO_ROOT" \
    :app:verifyRoborazziGithubFullDebug \
    :app:verifyRoborazziGithubSimpleDebug || return
  # verify_native_elfs.py and verify_native_sizes.py are skipped locally:
  # local debug builds only produce the host ABI with unstripped symbols,
  # while CI builds all 4 ABIs with stripped symbols. These checks only
  # make sense in the CI environment.
  echo "  (skipping verify_native_elfs.py and verify_native_sizes.py -- CI-only checks)"
}

run_native_native_bloat() {
  require_cmd cargo-bloat "cargo install cargo-bloat" || return
  python3 "$REPO_ROOT/scripts/ci/verify_native_bloat.py"
}

run_native_release_verification() {
  "$REPO_ROOT/gradlew" -p "$REPO_ROOT" :app:assembleRelease
}

run_native_kotlin_coverage() {
  "$REPO_ROOT/gradlew" -p "$REPO_ROOT" coverageReport -Pripdpi.skipNativeBuild=true
}

run_native_rust_coverage() {
  require_cmd cargo-llvm-cov "cargo install cargo-llvm-cov" || return
  # Rust coverage uses the CI-scoped native package report from run-rust-coverage.sh.
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
  local workflow="${JOB_WORKFLOW[$job]:-$DEFAULT_WORKFLOW}"
  local act_job="${JOB_ACT_ID[$job]:-$job}"
  local act_container_args=()
  local act_container_options="$ACT_CONTAINER_OPTIONS"
  local act_env_args=(
    --env "ACT=true"
    --env "GITHUB_EVENT_NAME=push"
    --env "RUNNER_TEMP=/tmp/runner-temp"
  )

  if ! command -v act &>/dev/null; then
    echo "ERROR: 'act' not installed (brew install act)" >&2
    return 1
  fi
  if ! docker info &>/dev/null 2>&1; then
    echo "ERROR: Docker not running" >&2
    return 1
  fi
  ensure_docker_desktop_credential_helper
  if [[ "$ACT_PACKET_CAPTURE_CAPS" == "true" ]]; then
    act_container_options="${act_container_options:+$act_container_options }--cap-add=NET_RAW --cap-add=NET_ADMIN"
  fi
  if [[ -n "$act_container_options" ]]; then
    act_container_args=(--container-options "$act_container_options")
  fi
  for passthrough_env in RIPDPI_PACKET_SMOKE_SCENARIO_FILTER RIPDPI_PACKET_SMOKE_CAPTURE_MODE; do
    if [[ -n "${!passthrough_env:-}" ]]; then
      act_env_args+=(--env "$passthrough_env=${!passthrough_env}")
    fi
  done

  act \
    -P "ubuntu-latest=${ACT_UBUNTU_IMAGE}" \
    --container-architecture "$ACT_CONTAINER_ARCH" \
    "${act_container_args[@]}" \
    --rm \
    -j "$act_job" \
    -W "$workflow" \
    -e "${ACT_DIR}/event-push.json" \
    "${act_env_args[@]}"
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
  for job in android-macrobenchmark android-network-e2e linux-tun-e2e linux-tun-soak rust-native-soak rust-native-load nightly-kotlin-coverage nightly-rust-coverage; do
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

    local effective_mode="$mode"
    [[ "$force_act" == "true" ]] && effective_mode="act"
    info "Running: $job (mode: $effective_mode)"
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
