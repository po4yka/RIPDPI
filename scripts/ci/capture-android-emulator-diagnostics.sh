#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ci/android-emulator-helpers.sh
source "$script_dir/android-emulator-helpers.sh"

avd_name=""
output_dir=""
logcat_file=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --avd)
      avd_name="$2"
      shift 2
      ;;
    --output-dir)
      output_dir="$2"
      shift 2
      ;;
    --logcat-file)
      logcat_file="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$output_dir" ]]; then
  echo "Usage: $0 --output-dir <dir> [--avd <name>] [--logcat-file <path>]" >&2
  exit 1
fi

capture_android_emulator_diagnostics "$output_dir" "$avd_name" "$logcat_file"
