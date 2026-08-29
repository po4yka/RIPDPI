#!/usr/bin/env bash

# Android 17 images use the published major.minor SDK identifier.
android_image_version() {
  case "$1" in
    37) printf '%s\n' '37.0' ;;
    *) printf '%s\n' "$1" ;;
  esac
}

is_android_sdk_root() {
  local candidate="$1"
  [[ -n "$candidate" && -d "$candidate" ]] || return 1

  [[ -x "$candidate/platform-tools/adb" ||
    -x "$candidate/emulator/emulator" ||
    -x "$candidate/cmdline-tools/latest/bin/avdmanager" ||
    -x "$candidate/cmdline-tools/bin/avdmanager" ]]
}

resolve_android_sdk_root() {
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]] && is_android_sdk_root "${ANDROID_SDK_ROOT}"; then
    printf '%s\n' "${ANDROID_SDK_ROOT}"
    return 0
  fi

  if [[ -n "${ANDROID_HOME:-}" ]] && is_android_sdk_root "${ANDROID_HOME}"; then
    printf '%s\n' "${ANDROID_HOME}"
    return 0
  fi

  local candidate
  for candidate in \
    "$HOME/Library/Android/sdk" \
    "$HOME/Android/Sdk" \
    "$HOME/android-sdk" \
    "$HOME/.android-sdk" \
    "$HOME/.local/share/android-sdk"
  do
    if is_android_sdk_root "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  local adb_bin=""
  if command -v adb >/dev/null 2>&1; then
    adb_bin="$(command -v adb)"
  fi
  if [[ -n "$adb_bin" ]]; then
    candidate="$(cd "$(dirname "$adb_bin")/.." && pwd)"
    if is_android_sdk_root "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi

  echo "::warning::Unable to resolve Android SDK root" >&2
  return 1
}

create_android_compiler_wrapper_dir() {
  if [[ $# -gt 0 && -n "$1" ]]; then
    mkdir -p "$1"
    printf '%s\n' "$1"
    return 0
  fi

  mktemp -d "${TMPDIR:-/tmp}/ripdpi-android-clang.XXXXXX"
}

write_android_compiler_filter_wrapper() {
  local wrapper_path="$1"
  local real_compiler="$2"

  mkdir -p "$(dirname "$wrapper_path")"
  cat > "$wrapper_path" <<EOF
#!/usr/bin/env bash
set -euo pipefail

real_compiler="$real_compiler"
filtered=()
skip_next=false

for arg in "\$@"; do
  if [[ "\$skip_next" == "true" ]]; then
    skip_next=false
    continue
  fi

  case "\$arg" in
    -arch|-isysroot)
      skip_next=true
      ;;
    -arch=*|-isysroot=*|-mmacosx-version-min=*|-miphoneos-version-min=*|-mios-simulator-version-min=*)
      ;;
    -Wl,-search_paths_first|-Wl,-headerpad_max_install_names|-Wl,-syslibroot,*|-Wl,-macosx_version_min,*|-Wl,-platform_version,*)
      ;;
    *)
      filtered+=("\$arg")
      ;;
  esac
done

exec "\$real_compiler" "\${filtered[@]}"
EOF
  chmod +x "$wrapper_path"
}

clean_android_boring_sys_build_cache() {
  local cargo_target_root="$1"
  local rust_target="$2"
  local build_root="$cargo_target_root/$rust_target/debug/build"

  [[ -d "$build_root" ]] || return 0
  find "$build_root" -maxdepth 1 -type d -name 'boring-sys-*' -exec rm -rf {} +
}

resolve_avdmanager_bin() {
  if command -v avdmanager >/dev/null 2>&1; then
    command -v avdmanager
    return 0
  fi

  local sdk_root
  sdk_root="$(resolve_android_sdk_root)" || return 1

  local candidate
  for candidate in \
    "$sdk_root/cmdline-tools/latest/bin/avdmanager" \
    "$sdk_root/cmdline-tools/bin/avdmanager"
  do
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  candidate="$(find "$sdk_root/cmdline-tools" -type f -path '*/bin/avdmanager' 2>/dev/null | sort | tail -n 1 || true)"
  if [[ -n "$candidate" && -x "$candidate" ]]; then
    printf '%s\n' "$candidate"
    return 0
  fi

  echo "::warning::Unable to resolve avdmanager binary" >&2
  return 1
}

resolve_emulator_bin() {
  if command -v emulator >/dev/null 2>&1; then
    command -v emulator
    return 0
  fi

  local sdk_root
  sdk_root="$(resolve_android_sdk_root)" || return 1

  local candidate="$sdk_root/emulator/emulator"
  if [[ -x "$candidate" ]]; then
    printf '%s\n' "$candidate"
    return 0
  fi

  echo "::warning::Unable to resolve emulator binary" >&2
  return 1
}

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

adb_cmd_timeout() {
  local timeout_seconds="$1"
  shift

  local adb_bin
  adb_bin="$(resolve_adb_bin)" || return 127
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    timeout --kill-after=2 "$timeout_seconds" "$adb_bin" -s "${ANDROID_SERIAL}" "$@"
  else
    timeout --kill-after=2 "$timeout_seconds" "$adb_bin" "$@"
  fi
}

adb_raw_timeout() {
  local timeout_seconds="$1"
  shift

  local adb_bin
  adb_bin="$(resolve_adb_bin)" || return 127
  timeout --kill-after=2 "$timeout_seconds" "$adb_bin" "$@"
}

has_adb_device() {
  local state
  state="$(adb_cmd_timeout 5 get-state 2>/dev/null | tr -d '\r' || true)"
  [[ "$state" == "device" ]]
}

wait_for_android_boot() {
  local timeout_seconds="${1:-600}"
  local sleep_seconds=2
  local deadline
  local attempt
  local max_attempts=$(((timeout_seconds + sleep_seconds - 1) / sleep_seconds))

  deadline="$(($(date +%s) + timeout_seconds))"
  adb_cmd_timeout 30 wait-for-device >/dev/null 2>&1 || true

  attempt=1
  while (( $(date +%s) < deadline )); do
    local sys_boot_completed
    local dev_bootcomplete

    sys_boot_completed="$(adb_cmd_timeout 2 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    dev_bootcomplete="$(adb_cmd_timeout 2 shell getprop dev.bootcomplete 2>/dev/null | tr -d '\r' || true)"

    if [[ "$sys_boot_completed" == "1" && "$dev_bootcomplete" == "1" ]] &&
      adb_cmd_timeout 5 shell pm list packages >/dev/null 2>&1; then
      return 0
    fi

    echo "Waiting for package manager... ($attempt/$max_attempts)"
    sleep "$sleep_seconds"
    attempt="$((attempt + 1))"
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

  # Preserve host-side boot evidence before querying a potentially wedged ADB.
  if [[ -n "$avd_name" ]]; then
    local metadata
    for metadata in system-image-source.properties system-image-package.xml system-image-id.txt; do
      if [[ -f "$HOME/.android/$avd_name/$metadata" ]]; then
        cp "$HOME/.android/$avd_name/$metadata" "$output_dir/$metadata"
      fi
    done
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

    if [[ -f "$HOME/.android/${avd_name}/avdmanager-create.log" ]]; then
      cp "$HOME/.android/${avd_name}/avdmanager-create.log" "$output_dir/avdmanager-create.log"
    else
      : > "$output_dir/avdmanager-create.log"
    fi
  else
    : > "$output_dir/emulator.log"
    : > "$output_dir/avd-config.ini"
    : > "$output_dir/avdmanager-create.log"
  fi

  adb_raw_timeout 10 devices -l >"$output_dir/adb-devices.txt" 2>&1 || true

  if has_adb_device; then
    adb_cmd_timeout 10 shell getprop >"$output_dir/device-getprop.txt" 2>&1 || true
    adb_cmd_timeout 5 shell pm path android >"$output_dir/package-manager-health.txt" 2>&1 || true
    adb_cmd_timeout 15 logcat -d >"$logcat_file" 2>&1 || true
  fi
}

stop_android_emulator() {
  local avd_name="$1"

  if has_adb_device; then
    adb_cmd_timeout 5 emu kill >/dev/null 2>&1 || true
  fi

  pkill -f "emulator .* -avd ${avd_name}( |$)" >/dev/null 2>&1 || true
}
