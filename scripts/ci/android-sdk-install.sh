#!/usr/bin/env bash
set -euo pipefail

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 <android-sdk-package>..." >&2
  exit 1
fi

attempts="${ANDROID_SDK_INSTALL_ATTEMPTS:-3}"
delay_seconds="${ANDROID_SDK_INSTALL_RETRY_DELAY_SECONDS:-20}"

dump_android_cli_crashes() {
  local crash_dir="${ANDROID_SDK_HOME:-$HOME}/.android/cli/analytics/crash_reports"

  if [[ ! -d "$crash_dir" ]]; then
    return 0
  fi

  find "$crash_dir" -type f -name '*.crash' 2>/dev/null |
    sort |
    tail -n 5 |
    while IFS= read -r crash_file; do
      echo "::group::Android CLI crash report: $crash_file"
      sed -n '1,220p' "$crash_file" || true
      echo "::endgroup::"
    done
}

for attempt in $(seq 1 "$attempts"); do
  echo "Installing Android SDK packages (attempt $attempt/$attempts): $*"
  if timeout 1200 android sdk install "$@"; then
    exit 0
  else
    status=$?
  fi

  dump_android_cli_crashes
  if [[ "$attempt" -eq "$attempts" ]]; then
    echo "::error::android sdk install failed after $attempts attempts" >&2
    exit "$status"
  fi

  sleep "$delay_seconds"
done
