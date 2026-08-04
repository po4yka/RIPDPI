#!/usr/bin/env bash
# check-jni-symbols.sh — Diff a native library's JNI exports against its baseline.
#
# Usage:
#   check-jni-symbols.sh <path-to-library.so> [<path-to-baseline>]
#
# Exit codes:
#   0  symbol surface matches baseline
#   1  unexpected diff (dropped or added symbols)
#   2  missing tool or argument
#
# Regen procedure:
#   Build libripdpi.so for a known-good commit, then run:
#     nm --dynamic --defined-only --extern-only <lib> \
#       | awk '{print $NF}' \
#       | grep -E '^(Java_|JNI_)' \
#       | LC_ALL=C sort \
#       > native/rust/crates/ripdpi-android/jni-symbols.baseline
#   Commit the updated baseline alongside the source change.
#   NOTE: --dynamic reads the .dynsym table, so this works on the optimized
#   (stripped) release .so too; the regular symbol table is empty there.

set -euo pipefail

LIB="${1:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
BASELINE="${2:-${REPO_ROOT}/native/rust/crates/ripdpi-android/jni-symbols.baseline}"

if [[ -z "${LIB}" ]]; then
    echo "error: usage: $0 <path-to-libripdpi.so> [<path-to-baseline>]" >&2
    exit 2
fi

if [[ ! -f "${LIB}" ]]; then
    echo "error: library not found: ${LIB}" >&2
    exit 2
fi

if [[ ! -f "${BASELINE}" ]]; then
    echo "error: baseline not found: ${BASELINE}" >&2
    exit 2
fi

# Pick the best available symbol reader.
if command -v llvm-nm &>/dev/null; then
    NM_CMD="llvm-nm"
elif command -v nm &>/dev/null; then
    NM_CMD="nm"
else
    echo "error: neither llvm-nm nor nm is available" >&2
    exit 2
fi

ACTUAL="$(mktemp)"
trap 'rm -f "${ACTUAL}"' EXIT

# --dynamic reads the .dynsym table: the JNI exports the JVM resolves via dlsym.
# This is what actually ships and survives symbol stripping in the optimized
# (release / android-jni) .so, whose regular symbol table is empty.
"${NM_CMD}" --dynamic --defined-only --extern-only "${LIB}" \
    | awk '{print $NF}' \
    | grep -E '^(Java_|JNI_)' \
    | LC_ALL=C sort \
    > "${ACTUAL}"

if diff --unified "${BASELINE}" "${ACTUAL}"; then
    echo "jni-symbol-diff: OK — symbol surface matches baseline"
    exit 0
else
    echo ""
    echo "jni-symbol-diff: FAIL — JNI symbol surface differs from baseline."
    echo "  baseline : ${BASELINE}"
    echo "  actual   : extracted from ${LIB}"
    echo ""
    echo "If this change is intentional, regenerate the baseline:"
    echo "  nm --dynamic --defined-only --extern-only <lib> \\"
    echo "    | awk '{print \$NF}' \\"
    echo "    | grep -E '^(Java_|JNI_)' \\"
    echo "    | LC_ALL=C sort \\"
    echo "    > native/rust/crates/ripdpi-android/jni-symbols.baseline"
    exit 1
fi
