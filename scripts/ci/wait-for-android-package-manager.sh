#!/usr/bin/env bash
set -euo pipefail

max_attempts="${1:-30}"
sleep_seconds="${2:-2}"

for attempt in $(seq 1 "$max_attempts"); do
  if adb shell pm list packages > /dev/null 2>&1; then
    exit 0
  fi

  echo "Waiting for package manager... ($attempt/$max_attempts)"
  sleep "$sleep_seconds"
done

echo "::error::Package manager unresponsive after $((max_attempts * sleep_seconds))s"
exit 1
