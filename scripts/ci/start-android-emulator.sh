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
no_wait=false

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
    --no-wait)
      no_wait=true
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$avd_name" || -z "$api_level" || -z "$arch" || -z "$target" ]]; then
  echo "Usage: $0 --avd <name> --api <level> --arch <arch> --target <target> [--ram 4096M] [--heap 1024M] [--disk 8G] [--boot-timeout 600] [--no-wait]" >&2
  exit 1
fi

image_version="$(android_image_version "$api_level")"
echo "Installing emulator SDK packages for android-${image_version}/${target}/${arch}"
# Android CLI 1.0 uses slash-notation package IDs (`system-images/android-NN/...`),
# not the legacy sdkmanager semicolon notation. See `android sdk install --help`.
bash "$script_dir/android-sdk-install.sh" \
  "cmdline-tools/latest" \
  "emulator" \
  "system-images/android-${image_version}/${target}/${arch}"

avdmanager_bin="$(resolve_avdmanager_bin)"
emulator_bin="$(resolve_emulator_bin)"
# Legacy semicolon notation is intentional here: this ID is passed to
# `avdmanager create avd --package`, not the Android CLI. avdmanager keeps the
# sdkmanager-style ID. Do not migrate this to slash notation.
sdk_id="system-images;android-${image_version};${target};${arch}"
android_user_home="${ANDROID_SDK_HOME:-$HOME}"
android_dot_dir="$android_user_home/.android"
avd_base_dir="$android_dot_dir/avd"
avd_dir="$avd_base_dir/${avd_name}.avd"
avd_ini="$avd_base_dir/${avd_name}.ini"
config_ini="$avd_dir/config.ini"
emulator_log_dir="$android_dot_dir/${avd_name}"
emulator_log_file="$emulator_log_dir/emulator.log"
create_log_file="$emulator_log_dir/avdmanager-create.log"
ram_mb="$(size_to_mb "$ram")"
heap_mb="$(size_to_mb "$heap")"

mkdir -p "$avd_base_dir" "$emulator_log_dir"
sdk_root="$(resolve_android_sdk_root)"
image_dir="$sdk_root/system-images/android-${image_version}/${target}/${arch}"
cp "$image_dir/source.properties" "$emulator_log_dir/system-image-source.properties"
cp "$image_dir/package.xml" "$emulator_log_dir/system-image-package.xml"
printf '%s\n' "$sdk_id" > "$emulator_log_dir/system-image-id.txt"
stop_android_emulator "$avd_name"
rm -rf "$avd_dir" "$avd_ini"

printf 'no\n' | "$avdmanager_bin" create avd \
  -n "$avd_name" \
  -k "$sdk_id" \
  -p "$avd_dir" \
  -f \
  >"$create_log_file" 2>&1

if [[ ! -f "$config_ini" ]]; then
  echo "::error::AVD config was not created at $config_ini" >&2
  cat "$create_log_file" >&2 || true
  find "$android_dot_dir" -maxdepth 3 \( -name "${avd_name}.ini" -o -name config.ini \) -print >&2 || true
  exit 1
fi

update_ini_property "$config_ini" "hw.ramSize" "$ram_mb"
update_ini_property "$config_ini" "vm.heapSize" "$heap_mb"
update_ini_property "$config_ini" "disk.dataPartition.size" "$disk"
update_ini_property "$config_ini" "fastboot.forceColdBoot" "yes"
update_ini_property "$config_ini" "avd.id" "$avd_name"
update_ini_property "$config_ini" "avd.name" "$avd_name"

cat >"$avd_ini" <<EOF
avd.ini.encoding=UTF-8
path=$avd_dir
path.rel=avd/${avd_name}.avd
target=android-${image_version}
EOF

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

if [[ "$no_wait" != "true" ]]; then
  wait_for_android_boot "$boot_timeout"
fi
