#!/usr/bin/env bash
# Run `adb shell monkey` against an installed app and disambiguate monkey's
# own self-exit from a real app crash.
#
# monkey(1) returns non-zero (typically 137 / -5) even on successful runs
# when its event budget is exhausted or it receives a quit signal. Naive
# wrappers treat that as a crash. The actual app-crashed signal is in
# logcat: lines tagged `ActivityManager` containing `PROCESS CRASHED` or
# `ANR in <pkg>`, plus `AndroidRuntime` `FATAL EXCEPTION` lines from the
# app process.
#
# This wrapper:
#   1. Clears the device logcat buffer.
#   2. Runs monkey with the requested event count against the requested package.
#   3. Captures the logcat range covering the monkey run.
#   4. Greps for true crash signals.
#   5. Exits 0 if logcat is clean (regardless of monkey's own exit code).
#   6. Exits 1 if a crash signal is present (with a tail of logcat).
#
# Usage:
#   scripts/test-monkey.sh [-p PACKAGE] [-c EVENTS] [-s SERIAL]
#
# Defaults:
#   PACKAGE = com.poyka.ripdpi
#   EVENTS  = 500
#   SERIAL  = (whichever adb defaults to)
#
# Exit codes:
#   0   no app crash detected
#   1   app crash detected (see logcat tail printed to stderr)
#   2   misuse (bad args, adb missing, package not installed)

set -uo pipefail

pkg="com.poyka.ripdpi"
events=500
serial_opt=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    -p) pkg="${2:?missing PACKAGE}"; shift 2 ;;
    -c) events="${2:?missing EVENT count}"; shift 2 ;;
    -s) serial_opt=(-s "${2:?missing SERIAL}"); shift 2 ;;
    -h|--help)
      sed -n '2,30p' "$0"
      exit 0
      ;;
    *)
      echo "test-monkey: unknown arg: $1" >&2
      exit 2
      ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "test-monkey: adb not on PATH" >&2
  exit 2
fi

if ! adb "${serial_opt[@]}" shell pm list packages "$pkg" 2>/dev/null | grep -q "^package:${pkg}$"; then
  echo "test-monkey: package not installed on device: $pkg" >&2
  exit 2
fi

adb "${serial_opt[@]}" logcat -c

# `--throttle 50` slows the spray to 20 events/sec so logcat keeps up.
# `--pct-syskeys 0 --pct-anyevent 0` avoids HOME/BACK/MENU edges that derail
# the app under test rather than exercising it. Touch events are the default
# bias and are what we want.
monkey_exit=0
adb "${serial_opt[@]}" shell monkey \
  -p "$pkg" \
  --throttle 50 \
  --pct-syskeys 0 \
  --pct-anyevent 0 \
  -v "$events" \
  >/dev/null 2>&1 \
  || monkey_exit=$?

# Drain logcat for the relevant signals.
logcat_dump="$(adb "${serial_opt[@]}" logcat -d 2>/dev/null || true)"

# Crash signals:
#   `FATAL EXCEPTION` from AndroidRuntime in our process.
#   `ANR in <pkg>` from ActivityManager.
#   `PROCESS CRASHED` from ActivityManager naming our package.
if printf '%s\n' "$logcat_dump" | grep -E "FATAL EXCEPTION|ANR in $pkg|Process $pkg .* has died|PROCESS CRASHED.*$pkg" >/dev/null; then
  echo "test-monkey: CRASH detected for $pkg (monkey exit was $monkey_exit)" >&2
  echo "test-monkey: ---- logcat tail ----" >&2
  printf '%s\n' "$logcat_dump" | tail -60 >&2
  exit 1
fi

# Clean run. monkey's self-exit is fine.
echo "test-monkey: no crash for $pkg after $events events (monkey exit was $monkey_exit)"
exit 0
