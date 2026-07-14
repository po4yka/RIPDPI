#!/usr/bin/env bash
# rust-stop-verify.sh — Stop hook
#
# Fires when Claude Code is about to stop the agentic turn. If any *.rs file
# is dirty in the working tree, runs fmt-check + clippy on the Rust workspace.
#
# Default mode (advisory): findings are printed to stderr but exit code is 0.
# This avoids blocking the turn when another agent in a parallel session has
# left unrelated drift in the working tree.
#
# Strict mode: set RIPDPI_RUST_HOOKS_STRICT=on to exit 2 on findings (injects
# stderr into the model's next-turn context). Use in solo sessions where every
# dirty .rs file is yours.
#
# Disable entirely: RIPDPI_RUST_HOOKS=off

set -uo pipefail

[[ "${RIPDPI_RUST_HOOKS:-on}" == "off" ]] && exit 0

strict_mode=0
[[ "${RIPDPI_RUST_HOOKS_STRICT:-off}" == "on" ]] && strict_mode=1

root=$(git -c core.fsmonitor=false rev-parse --show-toplevel 2>/dev/null || pwd)
ws="$root/native/rust"
[[ ! -f "$ws/Cargo.toml" ]] && exit 0

# Only run if Rust files are dirty in the working tree.
dirty_rs=$(cd "$root" && git -c core.fsmonitor=false status --porcelain -- '*.rs' 2>/dev/null | head -1)
[[ -z "$dirty_rs" ]] && exit 0

fmt_log=$(cd "$ws" && timeout 60 cargo fmt --all --check 2>&1) || fmt_ec=$?
fmt_ec=${fmt_ec:-0}

if [[ $fmt_ec -ne 0 ]]; then
  if [[ $strict_mode -eq 1 ]]; then
    echo "rust-stop-verify: cargo fmt --check FAILED (strict mode):" >&2
    echo "$fmt_log" | head -40 >&2
    exit 2
  else
    echo "rust-stop-verify [advisory]: cargo fmt --check found drift (RIPDPI_RUST_HOOKS_STRICT=on to block):" >&2
    echo "$fmt_log" | head -20 >&2
  fi
fi

clippy_log=$(cd "$ws" && timeout 180 cargo clippy --workspace --all-targets --locked --message-format=short -- -D warnings 2>&1) || clippy_ec=$?
clippy_ec=${clippy_ec:-0}

if [[ $clippy_ec -ne 0 ]]; then
  if [[ $strict_mode -eq 1 ]]; then
    echo "rust-stop-verify: cargo clippy --locked FAILED (strict mode):" >&2
    echo "$clippy_log" | tail -50 >&2
    exit 2
  else
    echo "rust-stop-verify [advisory]: cargo clippy --locked found warnings (RIPDPI_RUST_HOOKS_STRICT=on to block):" >&2
    echo "$clippy_log" | tail -30 >&2
  fi
fi

exit 0
