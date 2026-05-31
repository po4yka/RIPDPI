#!/usr/bin/env bash
# check-translation-export.sh -- CI gate for the translatable-key manifest.
#
# Regenerates the manifest from source into a temp file using the SAME
# extraction as export-strings-for-translation.sh, then diffs it against the
# committed config/i18n/translatable-keys.txt. If they differ -- i.e. a source
# string was added or removed without regenerating the manifest -- it prints the
# offending keys and exits 1. Exits 0 when in sync.
#
# This satisfies: CI fails the build if a new source string is added without
# being picked up by the pipeline export.
#
# Runnable from the repository root:
#   scripts/ci/check-translation-export.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

EXPORT_SCRIPT="scripts/ci/export-strings-for-translation.sh"
COMMITTED="config/i18n/translatable-keys.txt"

if [[ ! -f "$COMMITTED" ]]; then
    echo "ERROR: committed manifest missing: $COMMITTED" >&2
    echo "Run $EXPORT_SCRIPT and commit the result." >&2
    exit 1
fi

TMP_MANIFEST="$(mktemp)"
trap 'rm -f "$TMP_MANIFEST"' EXIT

# Regenerate with identical extraction by reusing the export script itself.
bash "$EXPORT_SCRIPT" "$TMP_MANIFEST" >/dev/null

if diff -q "$COMMITTED" "$TMP_MANIFEST" >/dev/null; then
    echo "OK: $COMMITTED is in sync with source strings."
    exit 0
fi

echo "ERROR: $COMMITTED is out of sync with the source string catalogs." >&2
echo "A translatable string was added or removed without regenerating the manifest." >&2
echo "Run '$EXPORT_SCRIPT' and commit the updated $COMMITTED." >&2
echo >&2

ADDED="$(comm -13 "$COMMITTED" "$TMP_MANIFEST" || true)"
REMOVED="$(comm -23 "$COMMITTED" "$TMP_MANIFEST" || true)"

if [[ -n "$ADDED" ]]; then
    echo "Keys present in source but MISSING from the committed manifest (add them):" >&2
    echo "$ADDED" | sed 's/^/  + /' >&2
fi
if [[ -n "$REMOVED" ]]; then
    echo "Keys in the committed manifest but NO LONGER in source (remove them):" >&2
    echo "$REMOVED" | sed 's/^/  - /' >&2
fi

exit 1
