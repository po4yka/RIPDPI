#!/usr/bin/env bash
# check-rules-drift.sh -- guard the .claude/rules/ <-> .codex/rules/ invariant.
#
# The canonical rules live in .claude/rules/*.md.  Every file there MUST have a
# byte-identical counterpart in .codex/rules/ (enforced today via symlinks; both
# directories are consumed by different tools and must stay in lockstep per CLAUDE.md).
#
# Three failure modes are checked:
#   1. A .claude/rules/*.md file has no counterpart in .codex/rules/ (missing symlink).
#   2. A .codex/rules/ entry is a broken symlink or a regular file that differs.
#   3. A .codex/rules/ regular file exists but is not byte-identical to its .claude/ source.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CLAUDE_DIR="$REPO_ROOT/.claude/rules"
CODEX_DIR="$REPO_ROOT/.codex/rules"

failed=0

# --- Check every .claude/rules/ file has a valid .codex/rules/ counterpart ---
for src in "$CLAUDE_DIR"/*.md; do
  base="$(basename "$src")"
  tgt="$CODEX_DIR/$base"

  # Missing entirely.
  if [ ! -e "$tgt" ] && [ ! -L "$tgt" ]; then
    echo "error: rules-drift — .codex/rules/$base is MISSING"
    echo "       Both directories must stay in lockstep (CLAUDE.md cross-tool rules)."
    echo "       Fix: ln -s ../../.claude/rules/$base .codex/rules/$base"
    failed=1
    continue
  fi

  # Broken symlink.
  if [ -L "$tgt" ] && [ ! -f "$tgt" ]; then
    echo "error: rules-drift — .codex/rules/$base is a BROKEN symlink (target not found)"
    echo "       Fix: rm .codex/rules/$base && ln -s ../../.claude/rules/$base .codex/rules/$base"
    failed=1
    continue
  fi

  # Regular file that differs (symlink replaced with a copy that drifted).
  if [ -f "$tgt" ] && ! [ -L "$tgt" ]; then
    if ! diff -q "$src" "$tgt" > /dev/null 2>&1; then
      echo "error: rules-drift — .codex/rules/$base is a regular file that differs from .claude/rules/$base"
      echo "       Both .claude/rules/ and .codex/rules/ must be kept byte-identical."
      echo "       Fix: cp .claude/rules/$base .codex/rules/$base  (or restore as symlink)"
      diff --unified=3 "$src" "$tgt" || true
      failed=1
    fi
  fi
done

# --- Check for .codex/rules/ files absent from .claude/rules/ ---
for tgt in "$CODEX_DIR"/*.md; do
  base="$(basename "$tgt")"
  src="$CLAUDE_DIR/$base"
  if [ ! -f "$src" ]; then
    echo "error: rules-drift — .codex/rules/$base has no counterpart in .claude/rules/"
    echo "       Both directories must move together. Remove or migrate the orphan file."
    failed=1
  fi
done

if [ "$failed" -ne 0 ]; then
  echo ""
  echo "  Hint: to create all missing symlinks from .claude/ to .codex/:"
  echo "    for f in .claude/rules/*.md; do"
  echo "      b=\$(basename \"\$f\")"
  echo "      [ -e \".codex/rules/\$b\" ] || ln -s \"../../.claude/rules/\$b\" \".codex/rules/\$b\""
  echo "    done"
  exit 1
fi
