#!/usr/bin/env bash
# Sourced helper for test-lab scripts. Surfaces disk / TMPDIR exhaustion
# with an actionable message before downstream tools (clang, gradle, cargo,
# docker) die with an opaque "No space left on device" or a misleading
# compile error.

# preflight_disk_space MIN_GIB [path]
#   Errors out if `path` (default $PWD) has less than MIN_GIB gibibytes
#   free. The 95%-full disk we hit during the May test pass surfaced as
#   clang temp-file failures that read like real compile errors; the
#   guard turns that into a one-line bail.
preflight_disk_space() {
  local min_gib="${1:?usage: preflight_disk_space MIN_GIB [path]}"
  local path="${2:-$PWD}"
  local avail_kb
  # `df -P` forces 9-column POSIX output so awk $4 is stable across macOS
  # (no --output flag) and Linux.
  avail_kb="$(df -k -P "$path" 2>/dev/null | awk 'NR==2 {print $4}')"
  if [[ -z "$avail_kb" ]]; then
    echo "preflight: could not determine free space at $path" >&2
    return 1
  fi
  local avail_gib=$((avail_kb / 1024 / 1024))
  if (( avail_gib < min_gib )); then
    cat >&2 <<MSG
preflight: only ${avail_gib} GiB free at ${path}; need >= ${min_gib} GiB.
  free space then retry. quick wins:
    docker system prune -af --volumes
    rm -rf ~/.gradle/caches/build-cache-*
    cargo clean --manifest-path native/rust/Cargo.toml
MSG
    return 1
  fi
  return 0
}

# preflight_tmpdir MIN_GIB
#   If the volume holding TMPDIR (or /tmp when unset) has less than
#   MIN_GIB free, redirect TMPDIR to a per-repo .tmp-build directory and
#   export it. clang in particular emits cryptic "No space left on device"
#   errors with no indication that /tmp is the offender; this rescues
#   builds on macOS where /tmp is on a small APFS volume.
preflight_tmpdir() {
  local min_gib="${1:?usage: preflight_tmpdir MIN_GIB}"
  local current_tmp="${TMPDIR:-/tmp}"
  local avail_kb
  avail_kb="$(df -k -P "$current_tmp" 2>/dev/null | awk 'NR==2 {print $4}')"
  if [[ -z "$avail_kb" ]]; then
    return 0  # silent: some sandboxed envs hide /tmp from df
  fi
  local avail_gib=$((avail_kb / 1024 / 1024))
  if (( avail_gib >= min_gib )); then
    return 0
  fi

  local fallback
  fallback="$(git rev-parse --show-toplevel 2>/dev/null || echo "$PWD")"
  fallback="${fallback}/.tmp-build"
  mkdir -p "$fallback"
  export TMPDIR="$fallback"
  echo "preflight: ${current_tmp} had only ${avail_gib} GiB; TMPDIR=${fallback}" >&2
}
