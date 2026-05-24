#!/usr/bin/env bash
# Serialize ./gradlew invocations via a per-repo lock.
#
# Background: gradle's configuration cache is not safe under concurrent
# writers in the same project. Two ./gradlew invocations started at the
# same time (e.g. a watcher + a CI script, or two parallel test loops)
# can corrupt .gradle/configuration-cache/ and produce errors like
# "Configuration cache state file is being written or read concurrently"
# or, worse, a corrupted cache that needs `./gradlew --rerun-tasks`.
#
# Usage:
#   scripts/gradle-serial.sh assembleDebug
#   scripts/gradle-serial.sh testDebugUnitTest --rerun-tasks
#   scripts/gradle-serial.sh :app:installDebug
#
# Args are forwarded verbatim to ./gradlew. Exit code is forwarded.
#
# Lock acquisition has a 30 minute timeout; beyond that the second caller
# bails with exit 124 (matches `timeout(1)` convention). Override with
# GRADLE_SERIAL_TIMEOUT_SEC.
#
# Uses flock(1) when available (Linux + macOS with `brew install flock`)
# and falls back to a mkdir(2)-based mutex elsewhere. The mkdir fallback
# self-cleans on signal-bearing exits via trap; a process killed with
# SIGKILL leaves the lock dir behind — remove it manually if seen:
#   rm -rf .gradle/.serial.lockd

set -euo pipefail

repo_root="$(git rev-parse --show-toplevel 2>/dev/null \
              || (cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd))"

if [[ ! -x "$repo_root/gradlew" ]]; then
  echo "gradle-serial: $repo_root/gradlew not found or not executable" >&2
  exit 1
fi

mkdir -p "$repo_root/.gradle"
timeout_sec="${GRADLE_SERIAL_TIMEOUT_SEC:-1800}"

if command -v flock >/dev/null 2>&1; then
  lock_file="$repo_root/.gradle/.serial.lock"
  exec flock --exclusive --timeout "$timeout_sec" "$lock_file" \
    "$repo_root/gradlew" "$@"
fi

# Portable mkdir-based mutex for envs without flock (default macOS).
lock_dir="$repo_root/.gradle/.serial.lockd"
deadline=$((SECONDS + timeout_sec))
while ! mkdir "$lock_dir" 2>/dev/null; do
  if (( SECONDS >= deadline )); then
    echo "gradle-serial: timed out (${timeout_sec}s) waiting for lock at $lock_dir" >&2
    echo "gradle-serial: if no other gradlew is running, remove the stale lock:" >&2
    echo "  rm -rf $lock_dir" >&2
    exit 124
  fi
  sleep 1
done
trap 'rmdir "$lock_dir" 2>/dev/null || true' EXIT INT TERM

"$repo_root/gradlew" "$@"
