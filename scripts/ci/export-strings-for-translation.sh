#!/usr/bin/env bash
# export-strings-for-translation.sh -- regenerate the canonical translatable-key
# manifest from source.
#
# Extracts every translatable <string name="..."> key from the two source string
# catalogs, EXCLUDING any string carrying translatable="false", sorts -u, and
# writes the result to config/i18n/translatable-keys.txt. App keys are prefixed
# with "app:" and core/service keys with "service:".
#
# The manifest is the contract between the in-repo source strings and the
# translation-export pipeline. check-translation-export.sh diffs a fresh
# extraction against the committed manifest so CI fails when a source string is
# added or removed without the manifest being regenerated.
#
# Idempotent and runnable from the repository root:
#   scripts/ci/export-strings-for-translation.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

APP_STRINGS="app/src/main/res/values/strings.xml"
SERVICE_STRINGS="core/service/src/main/res/values/strings.xml"
OUT_DIR="config/i18n"
# Output path may be overridden (used by check-translation-export.sh to write a
# temp manifest with the identical extraction logic). Defaults to the canonical
# committed manifest.
OUT_FILE="${1:-$OUT_DIR/translatable-keys.txt}"

# Emit "<prefix>:<key>" for every translatable <string name="KEY"> in $file.
# A <string ...> tag carrying translatable="false" is excluded. Only <string>
# entries are considered (not <plurals>, <string-array>, or comments).
extract_keys() {
    local file="$1"
    local prefix="$2"
    if [[ ! -f "$file" ]]; then
        echo "ERROR: source string file not found: $file" >&2
        exit 1
    fi
    # Match the opening <string name="..."> tag (possibly spanning attributes on
    # the same line), drop any that declare translatable="false", and capture the
    # key. grep -o keeps us robust to leading whitespace and trailing attributes.
    grep -oE '<string[[:space:]][^>]*name="[^"]+"[^>]*>' "$file" \
        | grep -v 'translatable="false"' \
        | sed -E 's/.*name="([^"]+)".*/'"$prefix"':\1/'
}

mkdir -p "$(dirname "$OUT_FILE")"

{
    extract_keys "$APP_STRINGS" "app"
    extract_keys "$SERVICE_STRINGS" "service"
} | sort -u > "$OUT_FILE"

COUNT="$(wc -l < "$OUT_FILE" | tr -d '[:space:]')"
echo "Wrote $COUNT translatable keys to $OUT_FILE"
