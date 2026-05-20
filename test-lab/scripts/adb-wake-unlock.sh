#!/usr/bin/env bash
set -euo pipefail

adb_bin="${ADB:-adb}"
settle_seconds="${RIPDPI_WAKE_UNLOCK_SETTLE_SECONDS:-1}"
max_attempts="${RIPDPI_WAKE_UNLOCK_ATTEMPTS:-5}"

adb_shell() {
  "$adb_bin" shell "$@" 2>/dev/null | tr -d '\r'
}

adb_state="$("$adb_bin" get-state 2>/dev/null | tr -d '\r' || true)"
if [[ "$adb_state" != "device" ]]; then
  echo "No ready Android target selected for wake/unlock; adb get-state returned '${adb_state:-unavailable}'." >&2
  exit 1
fi

keyguard_visible() {
  local window_state
  window_state="$(adb_shell dumpsys window || true)"
  grep -Eiq 'mDreamingLockscreen=true|mShowingLockscreen=true|isStatusBarKeyguard=true|mKeyguardShowing=true' <<<"$window_state"
}

wake_screen() {
  adb_shell input keyevent 224 >/dev/null 2>&1 || true
  adb_shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
}

dismiss_keyguard() {
  adb_shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb_shell input keyevent 82 >/dev/null 2>&1 || true
}

if ! [[ "$max_attempts" =~ ^[0-9]+$ ]] || [[ "$max_attempts" -lt 1 ]]; then
  echo "RIPDPI_WAKE_UNLOCK_ATTEMPTS must be a positive integer, got '$max_attempts'." >&2
  exit 2
fi

wake_screen
dismiss_keyguard

for ((attempt = 1; attempt <= max_attempts; attempt += 1)); do
  if ! keyguard_visible; then
    exit 0
  fi
  sleep "$settle_seconds"
  wake_screen
  dismiss_keyguard
done

echo "Android target still appears locked after wake/dismiss attempts; unlock the device or disable secure lock before running Maestro E2E flows." >&2
exit 1
