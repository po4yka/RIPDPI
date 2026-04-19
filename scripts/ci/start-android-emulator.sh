#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ci/android-emulator-helpers.sh
source "$script_dir/android-emulator-helpers.sh"

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

android sdk install "system-images;android-${api_level};${target};${arch}"

android emulator create \
  --name "$avd_name" \
  --api "$api_level" \
  --arch "$arch" \
  --target "$target" \
  --ram "$ram" \
  --heap "$heap" \
  --disk "$disk"

android emulator start \
  --avd "$avd_name" \
  --gpu swiftshader_indirect \
  --no-window \
  --no-audio \
  --no-boot-anim \
  --no-snapshot-save \
  --boot-timeout "$boot_timeout" \
  --detach

wait_for_android_boot "$boot_timeout"
