#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APPIUM_DIR="$ROOT_DIR/appium"
APPIUM_LOG="${RUNNER_TEMP:-/tmp}/appium-server.log"
APPIUM_PID=""

cleanup() {
  if [ -n "$APPIUM_PID" ]; then
    kill "$APPIUM_PID" 2>/dev/null || true
    wait "$APPIUM_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# -- prerequisites ------------------------------------------------------------

if ! command -v appium >/dev/null 2>&1; then
  echo "appium CLI is required on PATH" >&2
  exit 1
fi

if ! command -v pytest >/dev/null 2>&1; then
  echo "pytest is required on PATH (pip install -r appium/requirements.txt)" >&2
  exit 1
fi

timeout 60 adb wait-for-device

# -- install APK if not already present ---------------------------------------

APK_PATH="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
  adb install -r "$APK_PATH" || { echo "APK install failed" >&2; exit 1; }
else
  echo "Error: debug APK not found at $APK_PATH" >&2
  exit 1
fi

# -- start Appium server -----------------------------------------------------

mkdir -p "$(dirname "$APPIUM_LOG")"
appium --log-no-colors > "$APPIUM_LOG" 2>&1 &
APPIUM_PID=$!

echo "Waiting for Appium server (pid=$APPIUM_PID)..."
for i in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:4723/status >/dev/null 2>&1; then
    echo "Appium server ready"
    break
  fi
  if ! kill -0 "$APPIUM_PID" 2>/dev/null; then
    echo "Appium process died unexpectedly" >&2
    cat "$APPIUM_LOG" >&2
    exit 1
  fi
  if [ "$i" -eq 30 ]; then
    echo "Appium server failed to start within 30s" >&2
    cat "$APPIUM_LOG" >&2
    exit 1
  fi
  sleep 1
done

# -- run tests ----------------------------------------------------------------

mkdir -p "$APPIUM_DIR/screenshots"

cd "$APPIUM_DIR"
pytest tests/ \
  -v \
  --html=appium-report.html \
  --self-contained-html \
  --tb=short
