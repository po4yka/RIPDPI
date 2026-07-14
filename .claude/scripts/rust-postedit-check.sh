#!/usr/bin/env bash
# rust-postedit-check.sh — PostToolUse hook
#
# Fires after Edit/Write/MultiEdit/apply_patch. For *.rs files in native/rust/crates/,
# runs `cargo check` on the touched crate. Exits 2 with stderr piped on
# compile error so Claude Code injects the error back into model context
# for the next iteration. Skips silently for non-Rust files and for files
# outside the workspace.
#
# Disabled at the call site with: RIPDPI_RUST_HOOKS=off

set -uo pipefail

[[ "${RIPDPI_RUST_HOOKS:-on}" == "off" ]] && exit 0

input=$(cat 2>/dev/null) || exit 0
root=$(git -c core.fsmonitor=false rev-parse --show-toplevel 2>/dev/null || pwd)
f=$(printf '%s' "$input" | python3 "$root/.agents/hooks/extract_hook_paths.py" 2>/dev/null | grep -E '\.rs$' | head -1) || true
[[ -z "$f" ]] && exit 0
[[ "$f" = /* ]] || f="$root/$f"

# Locate enclosing Cargo.toml.
dir=$(dirname "$f")
crate=""
while [[ "$dir" != "/" && "$dir" != "." ]]; do
  if [[ -f "$dir/Cargo.toml" ]]; then
    crate=$(grep -E '^name\s*=' "$dir/Cargo.toml" 2>/dev/null | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
    [[ -n "$crate" ]] && break
  fi
  dir=$(dirname "$dir")
done
[[ -z "$crate" ]] && exit 0

# RIPDPI workspace lives at native/rust/.
ws="$root/native/rust"
[[ ! -f "$ws/Cargo.toml" ]] && exit 0

# Fast check (no codegen). Timeout 90s — agentic loop should not hang.
log=$(cd "$ws" && timeout 90 cargo check -p "$crate" --locked --message-format=short 2>&1) || ec=$?
ec=${ec:-0}

if [[ $ec -ne 0 ]]; then
  echo "rust-postedit-check FAILED for crate '$crate' (touched $f):" >&2
  echo "$log" | tail -40 >&2
  exit 2
fi
exit 0
