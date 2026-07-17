#!/usr/bin/env bash
# scripts/ci/check-locale-parity.sh
#   Diffs every locale's complete values XML set against the source values XML
#   set and exits 1 if any locale is missing keys. Stale extra keys are a warning.
#   Read-only: never modifies any file.
#
#   Satisfies: weekly CI gate that detects per-locale missing/extra keys vs.
#   app/src/main/res/values, app/src/simple/res/values, and
#   core/service/src/main/res/values.
#
#   Runnable from the repository root:
#     scripts/ci/check-locale-parity.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

LOCALES=()
while IFS= read -r locale; do
    [[ "$locale" == "en" ]] && continue
    if [[ "$locale" == *-* ]]; then
        language="${locale%%-*}"
        region="${locale##*-}"
        LOCALES+=("${language}-r${region}")
    else
        LOCALES+=("$locale")
    fi
done < <(
    sed -nE 's/.*android:name="([^"]+)".*/\1/p' \
        app/src/main/res/xml/locales_config.xml
)

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
    local tags
    tags="$(grep -oE '<string[[:space:]][^>]*name="[^"]+"[^>]*>' "$file" || true)"
    [[ -z "$tags" ]] && return
    printf '%s\n' "$tags" \
        | grep -v 'translatable="false"' \
        | sed -E 's/.*name="([^"]+)".*/\1/' \
        || true
}

extract_directory_keys() {
    local directory="$1"
    if [[ ! -d "$directory" ]]; then
        echo "ERROR: values directory not found: $directory" >&2
        exit 1
    fi
    while IFS= read -r file; do
        extract_keys "$file"
    done < <(find "$directory" -maxdepth 1 -type f -name '*.xml' | sort)
}

check_module() {
    local source_dir="$1"
    local values_dir="$2"
    local module="$3"

    local src_keys
    src_keys="$(extract_directory_keys "$source_dir" | sort -u)"
    local src_count
    src_count="$(echo "$src_keys" | wc -l | tr -d '[:space:]')"

    echo "==> $module: $src_count translatable source keys"

    for loc in "${LOCALES[@]}"; do
        local loc_dir="${values_dir}/values-${loc}"
        if [[ ! -d "$loc_dir" ]]; then
            echo "  MISSING DIRECTORY: $loc_dir" >&2
            FAIL=1
            continue
        fi

        local loc_keys
        loc_keys="$(extract_directory_keys "$loc_dir" | sort -u)"

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

check_module "app/src/main/res/values"          "app/src/main/res"          "app"
check_module "app/src/simple/res/values"        "app/src/simple/res"        "app/simple"
check_module "core/service/src/main/res/values" "core/service/src/main/res" "core/service"

echo ""
if [[ "$FAIL" -ne 0 ]]; then
    echo "LOCALE PARITY FAILED: $TOTAL_MISSING total missing key(s) across locales." >&2
    echo "Each missing key must be translated and committed before the next release." >&2
    exit 1
fi

echo "All locales are in parity with their source strings."
exit 0
