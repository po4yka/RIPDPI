#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APPIUM_DIR="$ROOT_DIR/appium"
APPIUM_LOG="${RUNNER_TEMP:-/tmp}/appium-server.log"
APPIUM_PID=""
PYTHON_BIN="${PYTHON_BIN:-python3}"
PYTEST_TARGETS=("$@")
if [ "${#PYTEST_TARGETS[@]}" -eq 0 ]; then
  PYTEST_TARGETS=(tests/)
fi

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

if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  echo "$PYTHON_BIN is required on PATH" >&2
  exit 1
fi

if ! "$PYTHON_BIN" - <<'PY' >/dev/null 2>&1
import appium
import pytest
import pytest_html
import selenium
PY
then
  echo "Appium Python dependencies are required (python3 -m pip install -r appium/requirements.txt)" >&2
  exit 1
fi

timeout 60 adb wait-for-device

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  for candidate in \
    "$HOME/Library/Android/sdk" \
    "${ANDROID_SDK_ROOT:-}" \
    "${ANDROID_HOME:-}"; do
    if [ -n "$candidate" ] && [ -d "$candidate/platform-tools" ]; then
      export ANDROID_HOME="$candidate"
      export ANDROID_SDK_ROOT="$candidate"
      break
    fi
  done
fi

# -- install APK if not already present ---------------------------------------

if [ -n "${APPIUM_APK_PATH:-}" ]; then
  APK_PATH="$APPIUM_APK_PATH"
else
  APK_PATH=""
  for candidate in \
    "$ROOT_DIR/app/build/outputs/apk/github/debug/app-github-universal-debug.apk" \
    "$ROOT_DIR/app/build/outputs/apk/debug/app-universal-debug.apk" \
    "$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"; do
    if [ -f "$candidate" ]; then
      APK_PATH="$candidate"
      break
    fi
  done
fi
if [ -f "$APK_PATH" ]; then
  adb install -r -d "$APK_PATH" || { echo "APK install failed" >&2; exit 1; }
else
  echo "Error: debug APK not found. Build :app:assembleGithubFullDebug or set APPIUM_APK_PATH." >&2
  exit 1
fi

# -- start Appium server -----------------------------------------------------

mkdir -p "$(dirname "$APPIUM_LOG")"
appium --log-no-colors --allow-insecure=uiautomator2:adb_shell > "$APPIUM_LOG" 2>&1 &
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
"$PYTHON_BIN" -m pytest "${PYTEST_TARGETS[@]}" \
  -v \
  --html=appium-report.html \
  --self-contained-html \
  --tb=short
