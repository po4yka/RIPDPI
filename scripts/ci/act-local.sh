#!/usr/bin/env bash
#
# Run CI workflows locally with act.
#
# Usage:
#   scripts/ci/act-local.sh [job-name|--all|--list]
#
# Examples:
#   scripts/ci/act-local.sh --list               # Show job compatibility matrix
#   scripts/ci/act-local.sh --all                 # Run all compatible jobs
#   scripts/ci/act-local.sh build                 # Run a specific job
#   scripts/ci/act-local.sh rust-network-e2e      # Lightest compatible job
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORKFLOW="${REPO_ROOT}/.github/workflows/ci.yml"
ACT_DIR="${REPO_ROOT}/.github/act"

# ── Job compatibility table ──────────────────────────────────────────
declare -A JOB_COMPAT=(
  [build]="yes"
  [static-analysis]="yes"
  [rust-network-e2e]="yes"
  [rust-native-soak]="yes"
  [android-network-e2e]="no"
  [linux-tun-e2e]="no"
  [linux-tun-soak]="no"
)

declare -A JOB_EVENT=(
  [build]="push"
  [static-analysis]="push"
  [rust-network-e2e]="push"
  [rust-native-soak]="workflow_dispatch"
  [android-network-e2e]="push"
  [linux-tun-e2e]="workflow_dispatch"
  [linux-tun-soak]="workflow_dispatch"
)

declare -A JOB_BLOCKER=(
  [android-network-e2e]="Emulator needs KVM (unavailable in Docker on macOS)"
  [linux-tun-e2e]="TUN device + sudo in Docker is fragile"
  [linux-tun-soak]="TUN device + sudo in Docker is fragile"
)

declare -A JOB_ALT=(
  [android-network-e2e]="./gradlew :app:connectedDebugAndroidTest on local emulator"
  [linux-tun-e2e]="CI-only or Linux VM"
  [linux-tun-soak]="CI-only or Linux VM"
)

COMPATIBLE_JOBS=(build static-analysis rust-network-e2e)

# ── Helpers ──────────────────────────────────────────────────────────
red()    { printf '\033[0;31m%s\033[0m\n' "$*"; }
green()  { printf '\033[0;32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[0;33m%s\033[0m\n' "$*"; }
bold()   { printf '\033[1m%s\033[0m\n' "$*"; }

event_file_for() {
  local event="${JOB_EVENT[$1]}"
  case "$event" in
    push)              echo "${ACT_DIR}/event-push.json" ;;
    pull_request)      echo "${ACT_DIR}/event-pr.json" ;;
    workflow_dispatch)  echo "${ACT_DIR}/event-dispatch.json" ;;
    *)                 echo "${ACT_DIR}/event-push.json" ;;
  esac
}

# ── Pre-flight checks ───────────────────────────────────────────────
preflight() {
  local ok=true

  if ! command -v act &>/dev/null; then
    red "ERROR: 'act' is not installed."
    echo "  Install: brew install act"
    ok=false
  fi

  if ! docker info &>/dev/null 2>&1; then
    red "ERROR: Docker is not running."
    echo "  Start Docker Desktop and try again."
    ok=false
  fi

  if [[ "$ok" == "false" ]]; then
    exit 1
  fi
}

# ── Commands ─────────────────────────────────────────────────────────
cmd_list() {
  bold "CI Job Compatibility Matrix (act on macOS)"
  echo ""
  printf "  %-25s %-12s %-50s %s\n" "JOB" "COMPATIBLE" "BLOCKER" "LOCAL ALTERNATIVE"
  printf "  %-25s %-12s %-50s %s\n" "---" "----------" "-------" "-----------------"
  for job in build static-analysis rust-network-e2e rust-native-soak android-network-e2e linux-tun-e2e linux-tun-soak; do
    local compat="${JOB_COMPAT[$job]}"
    local blocker="${JOB_BLOCKER[$job]:-—}"
    local alt="${JOB_ALT[$job]:-—}"
    local marker
    if [[ "$compat" == "yes" ]]; then
      marker="$(green "Yes")"
    else
      marker="$(red "No")"
    fi
    printf "  %-25s %-12b %-50s %s\n" "$job" "$marker" "$blocker" "$alt"
  done
  echo ""
  echo "Tip: rust-native-soak needs dispatch event payload (used automatically)."
}

run_job() {
  local job="$1"

  if [[ -z "${JOB_COMPAT[$job]+x}" ]]; then
    red "ERROR: Unknown job '$job'."
    echo "  Run with --list to see available jobs."
    exit 1
  fi

  if [[ "${JOB_COMPAT[$job]}" == "no" ]]; then
    yellow "SKIP: '$job' is not act-compatible on macOS."
    echo "  Blocker: ${JOB_BLOCKER[$job]}"
    echo "  Alternative: ${JOB_ALT[$job]}"
    return 0
  fi

  local event_file
  event_file="$(event_file_for "$job")"
  local event_name="${JOB_EVENT[$job]}"

  bold "Running job: $job (event: $event_name)"
  echo ""

  act \
    -j "$job" \
    -W "$WORKFLOW" \
    -e "$event_file" \
    --env "GITHUB_EVENT_NAME=${event_name}" \
    --env "RUNNER_TEMP=/tmp/runner-temp" \
    || {
      local rc=$?
      red "Job '$job' failed (exit $rc)."
      echo ""
      echo "Troubleshooting: see .github/skills/local-ci-act/SKILL.md"
      return $rc
    }

  green "Job '$job' passed."
}

cmd_all() {
  local failed=()
  for job in "${COMPATIBLE_JOBS[@]}"; do
    echo ""
    if ! run_job "$job"; then
      failed+=("$job")
    fi
  done

  echo ""
  if [[ ${#failed[@]} -gt 0 ]]; then
    red "Failed jobs: ${failed[*]}"
    exit 1
  else
    green "All compatible jobs passed."
  fi
}

# ── Main ─────────────────────────────────────────────────────────────
main() {
  local arg="${1:---list}"

  case "$arg" in
    --list|-l)
      cmd_list
      ;;
    --all|-a)
      preflight
      cmd_all
      ;;
    --help|-h|help)
      echo "Usage: scripts/ci/act-local.sh [job-name|--all|--list]"
      echo ""
      echo "Options:"
      echo "  --list, -l   Show job compatibility matrix"
      echo "  --all, -a    Run all compatible jobs sequentially"
      echo "  --help, -h   Show this help"
      echo ""
      echo "Jobs: build, static-analysis, rust-network-e2e, rust-native-soak"
      ;;
    *)
      # Check compatibility before requiring Docker
      if [[ -n "${JOB_COMPAT[$arg]+x}" && "${JOB_COMPAT[$arg]}" == "no" ]]; then
        run_job "$arg"
      else
        preflight
        run_job "$arg"
      fi
      ;;
  esac
}

main "$@"
