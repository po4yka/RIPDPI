#!/usr/bin/env bash
# scripts/ci/check-locale-parity.sh
#   Diffs every locale strings.xml against the source strings and exits 1 if
#   any locale is missing keys. Stale extra keys in a locale are a warning only.
#   Read-only: never modifies any file.
#
#   Satisfies: weekly CI gate that detects per-locale missing/extra keys vs.
#   app/src/main/res/values/strings.xml and
#   core/service/src/main/res/values/strings.xml.
#
#   Runnable from the repository root:
#     scripts/ci/check-locale-parity.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

APP_STRINGS="app/src/main/res/values/strings.xml"
SERVICE_STRINGS="core/service/src/main/res/values/strings.xml"

# Must be kept in sync with app/src/main/res/xml/locales_config.xml.
LOCALES=(ru es de fr fa ar zh-rCN)

FAIL=0
TOTAL_MISSING=0

# Identical extraction to export-strings-for-translation.sh:
# matches <string name="KEY"> tags, excludes translatable="false".
extract_keys() {
    local file="$1"
    if [[ ! -f "$file" ]]; then
        echo "ERROR: source file not found: $file" >&2
        exit 1
    fi
    grep -oE '<string[[:space:]][^>]*name="[^"]+"[^>]*>' "$file" \
        | grep -v 'translatable="false"' \
        | sed -E 's/.*name="([^"]+)".*/\1/'
}

check_module() {
    local src="$1"
    local values_dir="$2"
    local module="$3"

    local src_keys
    src_keys="$(extract_keys "$src" | sort -u)"
    local src_count
    src_count="$(echo "$src_keys" | wc -l | tr -d '[:space:]')"

    echo "==> $module: $src_count translatable source keys"

    for loc in "${LOCALES[@]}"; do
        local loc_file="${values_dir}/values-${loc}/strings.xml"
        if [[ ! -f "$loc_file" ]]; then
            echo "  MISSING FILE: $loc_file" >&2
            FAIL=1
            continue
        fi

        local loc_keys
        loc_keys="$(extract_keys "$loc_file" | sort -u)"

        local missing
        missing="$(comm -23 <(echo "$src_keys") <(echo "$loc_keys") || true)"
        local missing_count=0
        [[ -n "$missing" ]] && missing_count="$(echo "$missing" | wc -l | tr -d '[:space:]')"

        local extra
        extra="$(comm -13 <(echo "$src_keys") <(echo "$loc_keys") || true)"
        local extra_count=0
        [[ -n "$extra" ]] && extra_count="$(echo "$extra" | wc -l | tr -d '[:space:]')"

        if [[ "$missing_count" -eq 0 && "$extra_count" -eq 0 ]]; then
            echo "  OK    values-${loc}: in parity"
        else
            if [[ "$missing_count" -gt 0 ]]; then
                echo "  DRIFT values-${loc}: $missing_count key(s) missing from locale" >&2
                echo "$missing" | sed 's/^/    - /' >&2
                TOTAL_MISSING=$((TOTAL_MISSING + missing_count))
                FAIL=1
            fi
            if [[ "$extra_count" -gt 0 ]]; then
                # Extra keys are stale (removed from source); warning only — they
                # do not cause a build failure, but should be cleaned up.
                echo "  WARN  values-${loc}: $extra_count key(s) in locale but not in source (stale)"
            fi
        fi
    done
}

check_module "$APP_STRINGS"     "app/src/main/res"          "app"
check_module "$SERVICE_STRINGS" "core/service/src/main/res" "core/service"

echo ""
if [[ "$FAIL" -ne 0 ]]; then
    echo "LOCALE PARITY FAILED: $TOTAL_MISSING total missing key(s) across locales." >&2
    echo "Each missing key must be translated and committed before the next release." >&2
    exit 1
fi

echo "All locales are in parity with their source strings."
exit 0
