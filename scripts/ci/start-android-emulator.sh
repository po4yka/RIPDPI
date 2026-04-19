#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ci/android-emulator-helpers.sh
source "$script_dir/android-emulator-helpers.sh"

update_ini_property() {
  local file="$1"
  local key="$2"
  local value="$3"
  local temp_file

  temp_file="$(mktemp)"
  if [[ -f "$file" ]]; then
    awk -F= -v key="$key" -v value="$value" '
      BEGIN { updated = 0 }
      $1 == key {
        print key "=" value
        updated = 1
        next
      }
      { print }
      END {
        if (updated == 0) {
          print key "=" value
        }
      }
    ' "$file" >"$temp_file"
  else
    printf '%s=%s\n' "$key" "$value" >"$temp_file"
  fi

  mv "$temp_file" "$file"
}

size_to_mb() {
  local raw="$1"
  local upper="${raw^^}"

  if [[ "$upper" =~ ^([0-9]+)G$ ]]; then
    printf '%s\n' "$((BASH_REMATCH[1] * 1024))"
    return 0
  fi

  if [[ "$upper" =~ ^([0-9]+)M$ ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
    return 0
  fi

  printf '%s\n' "$raw"
}

avd_name=""
api_level=""
arch=""
target=""
ram="4096M"
heap="1024M"
disk="8G"
boot_timeout="600"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --avd)
      avd_name="$2"
      shift 2
      ;;
    --api)
      api_level="$2"
      shift 2
      ;;
    --arch)
      arch="$2"
      shift 2
      ;;
    --target)
      target="$2"
      shift 2
      ;;
    --ram)
      ram="$2"
      shift 2
      ;;
    --heap)
      heap="$2"
      shift 2
      ;;
    --disk)
      disk="$2"
      shift 2
      ;;
    --boot-timeout)
      boot_timeout="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$avd_name" || -z "$api_level" || -z "$arch" || -z "$target" ]]; then
  echo "Usage: $0 --avd <name> --api <level> --arch <arch> --target <target> [--ram 4096M] [--heap 1024M] [--disk 8G] [--boot-timeout 600]" >&2
  exit 1
fi

android sdk install \
  "cmdline-tools;latest" \
  "emulator" \
  "system-images;android-${api_level};${target};${arch}"

avdmanager_bin="$(resolve_avdmanager_bin)"
emulator_bin="$(resolve_emulator_bin)"
sdk_id="system-images;android-${api_level};${target};${arch}"
avd_dir="$HOME/.android/avd/${avd_name}.avd"
avd_ini="$HOME/.android/avd/${avd_name}.ini"
config_ini="$avd_dir/config.ini"
emulator_log_dir="$HOME/.android/${avd_name}"
emulator_log_file="$emulator_log_dir/emulator.log"
ram_mb="$(size_to_mb "$ram")"
heap_mb="$(size_to_mb "$heap")"

mkdir -p "$HOME/.android/avd" "$emulator_log_dir"
stop_android_emulator "$avd_name"
rm -rf "$avd_dir" "$avd_ini"

echo "no" | "$avdmanager_bin" create avd \
  -n "$avd_name" \
  -k "$sdk_id" \
  -f

update_ini_property "$config_ini" "hw.ramSize" "$ram_mb"
update_ini_property "$config_ini" "vm.heapSize" "$heap_mb"
update_ini_property "$config_ini" "disk.dataPartition.size" "$disk"
update_ini_property "$config_ini" "fastboot.forceColdBoot" "yes"

rm -f "$emulator_log_file"
nohup "$emulator_bin" \
  -avd "$avd_name" \
  -memory "$ram_mb" \
  -gpu swiftshader_indirect \
  -no-window \
  -no-audio \
  -no-boot-anim \
  -no-snapshot \
  >"$emulator_log_file" 2>&1 </dev/null &

wait_for_android_boot "$boot_timeout"
