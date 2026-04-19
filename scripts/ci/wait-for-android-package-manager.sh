#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ci/android-emulator-helpers.sh
source "$script_dir/android-emulator-helpers.sh"

max_attempts="${1:-30}"
sleep_seconds="${2:-2}"
wait_for_android_boot "$((max_attempts * sleep_seconds))"
