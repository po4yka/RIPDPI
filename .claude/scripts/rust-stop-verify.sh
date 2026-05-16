#!/usr/bin/env bash
# rust-stop-verify.sh — Stop hook
#
# Fires when Claude Code is about to stop the agentic turn. If any *.rs file
# was modified in this session (working tree), runs fmt-check + clippy on the
# Rust workspace. Exits 2 with stderr piped on any failure so the model sees
# the gap and can fix before the turn ends.
#
# Disabled at the call site with: RIPDPI_RUST_HOOKS=off

set -uo pipefail

[[ "${RIPDPI_RUST_HOOKS:-on}" == "off" ]] && exit 0

root=$(git -c core.fsmonitor=false rev-parse --show-toplevel 2>/dev/null || pwd)
ws="$root/native/rust"
[[ ! -f "$ws/Cargo.toml" ]] && exit 0

# Only run if Rust files are dirty in the working tree.
dirty_rs=$(cd "$root" && git -c core.fsmonitor=false status --porcelain -- '*.rs' 2>/dev/null | head -1)
[[ -z "$dirty_rs" ]] && exit 0

fmt_log=$(cd "$ws" && timeout 60 cargo fmt --all --check 2>&1) || fmt_ec=$?
fmt_ec=${fmt_ec:-0}

if [[ $fmt_ec -ne 0 ]]; then
  echo "rust-stop-verify: cargo fmt --check FAILED:" >&2
  echo "$fmt_log" | head -40 >&2
  exit 2
fi

clippy_log=$(cd "$ws" && timeout 180 cargo clippy --workspace --all-targets --locked --message-format=short -- -D warnings 2>&1) || clippy_ec=$?
clippy_ec=${clippy_ec:-0}

if [[ $clippy_ec -ne 0 ]]; then
  echo "rust-stop-verify: cargo clippy FAILED:" >&2
  echo "$clippy_log" | tail -50 >&2
  exit 2
fi

exit 0
