#!/usr/bin/env bash

resolve_adb_bin() {
  if [[ -n "${ADB_BIN:-}" && -x "${ADB_BIN}" ]]; then
    printf '%s\n' "${ADB_BIN}"
    return 0
  fi

  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return 0
  fi

  local candidates=()
  local sdk_root
  for sdk_root in \
    "${ANDROID_SDK_ROOT:-}" \
    "${ANDROID_HOME:-}" \
    "$HOME/Android/Sdk" \
    "$HOME/android-sdk" \
    "$HOME/.android-sdk" \
    "$HOME/.local/share/android-sdk"
  do
    [[ -n "$sdk_root" ]] || continue
    candidates+=("$sdk_root/platform-tools/adb")
  done

  local candidate
  for candidate in "${candidates[@]}"; do
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  local found
  found="$(find "$HOME" -type f -path '*/platform-tools/adb' 2>/dev/null | head -n 1 || true)"
  if [[ -n "$found" && -x "$found" ]]; then
    printf '%s\n' "$found"
    return 0
  fi

  echo "::warning::Unable to resolve adb binary" >&2
  return 1
}

adb_raw() {
  local adb_bin
  adb_bin="$(resolve_adb_bin)" || return 127
  "$adb_bin" "$@"
}

adb_cmd() {
  local adb_bin
  adb_bin="$(resolve_adb_bin)" || return 127
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    "$adb_bin" -s "${ANDROID_SERIAL}" "$@"
  else
    "$adb_bin" "$@"
  fi
}

has_adb_device() {
  local state
  state="$(adb_cmd get-state 2>/dev/null | tr -d '\r' || true)"
  [[ "$state" == "device" ]]
}

wait_for_android_boot() {
  local timeout_seconds="${1:-600}"
  local sleep_seconds=2
  local max_attempts=$(((timeout_seconds + sleep_seconds - 1) / sleep_seconds))
  local attempt

  adb_cmd wait-for-device >/dev/null 2>&1 || true

  for attempt in $(seq 1 "$max_attempts"); do
    local sys_boot_completed
    local dev_bootcomplete

    sys_boot_completed="$(adb_cmd shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    dev_bootcomplete="$(adb_cmd shell getprop dev.bootcomplete 2>/dev/null | tr -d '\r' || true)"

    if [[ "$sys_boot_completed" == "1" && "$dev_bootcomplete" == "1" ]] &&
      adb_cmd shell pm list packages >/dev/null 2>&1; then
      return 0
    fi

    echo "Waiting for package manager... ($attempt/$max_attempts)"
    sleep "$sleep_seconds"
  done

  echo "::error::Package manager unresponsive after ${timeout_seconds}s"
  return 1
}

capture_android_emulator_diagnostics() {
  local output_dir="$1"
  local avd_name="${2:-}"
  local logcat_file="${3:-$output_dir/android-logcat.txt}"

  mkdir -p "$output_dir"
  : > "$logcat_file"
  : > "$output_dir/adb-devices.txt"
  : > "$output_dir/device-getprop.txt"
  : > "$output_dir/package-manager-health.txt"

  adb_raw devices -l >"$output_dir/adb-devices.txt" 2>&1 || true

  if has_adb_device; then
    adb_cmd shell getprop >"$output_dir/device-getprop.txt" 2>&1 || true
    adb_cmd shell pm path android >"$output_dir/package-manager-health.txt" 2>&1 || true
    adb_cmd logcat -d >"$logcat_file" 2>&1 || true
  fi

  if [[ -n "$avd_name" ]]; then
    if [[ -f "$HOME/.android/$avd_name/emulator.log" ]]; then
      cp "$HOME/.android/$avd_name/emulator.log" "$output_dir/emulator.log"
    else
      : > "$output_dir/emulator.log"
    fi

    if [[ -f "$HOME/.android/avd/${avd_name}.avd/config.ini" ]]; then
      cp "$HOME/.android/avd/${avd_name}.avd/config.ini" "$output_dir/avd-config.ini"
    else
      : > "$output_dir/avd-config.ini"
    fi
  else
    : > "$output_dir/emulator.log"
    : > "$output_dir/avd-config.ini"
  fi
}

stop_android_emulator() {
  local avd_name="$1"

  if command -v android >/dev/null 2>&1; then
    android emulator stop --avd "$avd_name" >/dev/null 2>&1 ||
      android emulator stop "$avd_name" >/dev/null 2>&1 ||
      true
  fi

  if has_adb_device; then
    adb_cmd emu kill >/dev/null 2>&1 || true
  fi
}
