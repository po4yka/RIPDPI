#!/usr/bin/env bash
#
# Run RIPDPI Journeys (Android CLI 1.0) against a connected device/emulator.
#
# Journeys are natural-language, AI-agent-driven UI tests executed by
# `android journeys`. This is an opt-in scaffolding lane: until an AI agent is
# wired into the CI runner, the script installs the app, reports the missing
# journey-runner prerequisite, and exits 0 rather than failing the build.
#
# Verification gates (see journeys/README.md):
#   * the exact `android journeys` run subcommand/flags -- confirm via
#     `android journeys --help` once the CLI is installed;
#   * the journey XML schema -- confirm via the Android CLI 1.0 journey template.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JOURNEY_DIR="$ROOT_DIR/journeys"
ARTIFACT_DIR="${RUNNER_TEMP:-/tmp}/journeys"
APP_ID="com.poyka.ripdpi"

mkdir -p "$ARTIFACT_DIR"

if ! command -v android >/dev/null 2>&1; then
  echo "Android CLI ('android') is required -- see d.android.com/tools/agents." >&2
  exit 1
fi

shopt -s nullglob
journeys=("$JOURNEY_DIR"/*.journey)
shopt -u nullglob
if [[ ${#journeys[@]} -eq 0 ]]; then
  echo "No .journey files found under $JOURNEY_DIR" >&2
  exit 1
fi

adb wait-for-device

# --- Install the app under test -------------------------------------------
# Use a prebuilt APK when RIPDPI_JOURNEYS_APK is set; otherwise assemble the
# github/debug variant (the variant exercised by the connected test lanes).
apk="${RIPDPI_JOURNEYS_APK:-}"
if [[ -z "$apk" ]]; then
  echo "Assembling :app:assembleGithubDebug ..."
  ( cd "$ROOT_DIR" && ./gradlew --no-daemon :app:assembleGithubDebug )
  apk="$(find "$ROOT_DIR/app/build/outputs/apk/github/debug" -name '*.apk' -print -quit 2>/dev/null || true)"
fi
if [[ -z "$apk" || ! -f "$apk" ]]; then
  echo "Could not locate the app APK to install (set RIPDPI_JOURNEYS_APK)." >&2
  exit 1
fi
echo "Installing $apk"
adb install -r -d "$apk"

# --- Journey runner prerequisite check ------------------------------------
# Journeys are driven by an AI agent's vision/reasoning. Confirm the installed
# Android CLI exposes the journey runner before attempting a run; if it does
# not, skip cleanly -- this lane is opt-in scaffolding, not yet a merge gate.
if ! android journeys --help >/dev/null 2>&1; then
  echo "::notice::'android journeys' runner unavailable in this Android CLI build."
  echo "::notice::App installed; journeys not executed. See journeys/README.md gate #2/#3."
  exit 0
fi

# --- Run each journey ------------------------------------------------------
# The app ID is supplied to the journey runner via the environment, not the
# journey files (Android CLI uses JOURNEYS_CUSTOM_APP_ID for pre-installed apps).
export JOURNEYS_CUSTOM_APP_ID="$APP_ID"
serial="$(adb get-serialno)"
status=0
for journey in "${journeys[@]}"; do
  name="$(basename "$journey")"
  log="$ARTIFACT_DIR/${name%.journey}.log"
  echo "=== Running journey: $name ==="
  # VERIFICATION GATE: confirm the exact subcommand and flags against
  # `android journeys --help`. Best-effort invocation below.
  if android journeys run "$journey" --device "$serial" >"$log" 2>&1; then
    echo "PASS: $name"
  else
    echo "FAIL: $name (see $log)"
    status=1
  fi
done

# Pull any on-device journey artifacts (screenshots / reasoning traces).
adb pull "/sdcard/Android/data/$APP_ID/files/journeys" "$ARTIFACT_DIR/device" 2>/dev/null || true

exit "$status"
