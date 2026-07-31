#!/usr/bin/env bash
#
# Prepare a connected device/emulator for an agent-driven Android CLI Journeys
# run, and smoke-test the journey primitives.
#
# Android CLI 1.0 has NO `android journeys` command -- Journeys are executed by
# an AI agent (the android-test-runner agent, Gemini, etc.) that drives the app
# with `android screen capture -a`, `android screen resolve`, `android layout`,
# and `android run`, guided by the bundled `android-cli` skill. A shell script
# cannot perform the vision/reasoning loop, so this script only:
#   1. installs the app under test,
#   2. verifies the device + the journey primitives respond,
#   3. lists the journeys awaiting agent execution,
# then exits 0. Wiring an agent into CI to fully execute journeys is a separate
# follow-up. See journeys/README.md.

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
  echo "Assembling :app:assembleGithubFullDebug ..."
  pt_gradle_args=()
  if [[ -n "${RIPDPI_PREBUILT_PT_DIR:-}" || -n "${RIPDPI_PREBUILT_PT_MANIFEST_SHA256:-}" ]]; then
    : "${RIPDPI_PREBUILT_PT_DIR:?RIPDPI_PREBUILT_PT_DIR is required with a prebuilt PT digest}"
    : "${RIPDPI_PREBUILT_PT_MANIFEST_SHA256:?RIPDPI_PREBUILT_PT_MANIFEST_SHA256 is required with prebuilt PT assets}"
    pt_gradle_args=(
      "-Pripdpi.prebuiltPluggableTransportAssetsDir=$RIPDPI_PREBUILT_PT_DIR"
      "-Pripdpi.prebuiltPluggableTransportAssetsManifestSha256=$RIPDPI_PREBUILT_PT_MANIFEST_SHA256"
    )
  fi
  ( cd "$ROOT_DIR" && ./gradlew --no-daemon --profile :app:assembleGithubFullDebug "${pt_gradle_args[@]}" )
  apk="$(find "$ROOT_DIR/app/build/outputs/apk/github/debug" -name '*.apk' -print -quit 2>/dev/null || true)"
fi
if [[ -z "$apk" || ! -f "$apk" ]]; then
  echo "Could not locate the app APK to install (set RIPDPI_JOURNEYS_APK)." >&2
  exit 1
fi
echo "Installing $apk"
adb install -r -d "$apk"

# --- Smoke-test the journey primitives ------------------------------------
# These are the Android CLI commands an agent uses to execute a journey.
# Capturing an annotated screenshot and dumping the layout tree proves the CLI
# can see the device; if either fails, an agent journey run would fail too.
echo "Verifying journey primitives (screen capture / layout) ..."
android --no-metrics screen capture --annotate --output "$ARTIFACT_DIR/primitive-check.png"
android --no-metrics layout --output "$ARTIFACT_DIR/primitive-check.layout.json"

# --- Hand off to the agent -------------------------------------------------
echo
echo "Device prepared. ${#journeys[@]} journey(s) await agent execution:"
for journey in "${journeys[@]}"; do
  echo "  - $(basename "$journey")"
done
echo
echo "Android CLI 1.0 has no 'android journeys' command. To run a journey, an"
echo "agent drives each step with: android screen capture --annotate -> reason"
echo "over the labeled screenshot -> android screen resolve --string 'input tap"
echo "#N' -> adb shell input ... -> android layout for assertions. The app ID"
echo "for pre-installed runs is JOURNEYS_CUSTOM_APP_ID=$APP_ID."
echo "See journeys/README.md and the android-test-runner agent."
