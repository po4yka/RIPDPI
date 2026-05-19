#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"

TEST_TOOL="${MUTANTS_TEST_TOOL:-nextest}"
PACKAGES="${MUTANTS_PACKAGES:-}"
JOBS="${MUTANTS_JOBS:-}"
OUTPUT_DIR="${MUTANTS_OUTPUT_DIR:-$repo_root/target/mutants-output}"

mkdir -p "$(dirname "$OUTPUT_DIR")"

common_args=(--test-tool "$TEST_TOOL" --output "$OUTPUT_DIR")
if [ -n "$JOBS" ]; then
    if [[ ! "$JOBS" =~ ^[0-9]+$ ]]; then
        echo "error: MUTANTS_JOBS must be a numeric cargo-mutants --jobs value, got: $JOBS" >&2
        exit 2
    fi
    common_args+=(--jobs "$JOBS")
fi

workspace_packages() {
    local manifest="$1"
    cargo metadata --manifest-path "$manifest" --format-version 1 --no-deps | jq -r '.packages[].name'
}

package_belongs_to_workspace() {
    local pkg="$1"
    shift
    local candidate
    for candidate in "$@"; do
        if [ "$candidate" = "$pkg" ]; then
            return 0
        fi
    done
    return 1
}

run_workspace_mutants() {
    local label="$1"
    local manifest="$2"
    shift 2
    local extra_arg_count="$#"
    local args=("${common_args[@]}")

    if [ -n "$PACKAGES" ]; then
        available_packages=()
        while IFS= read -r pkg; do
            available_packages+=("$pkg")
        done < <(workspace_packages "$manifest")
        matching_packages=()
        local pkg
        for pkg in $PACKAGES; do
            if package_belongs_to_workspace "$pkg" "${available_packages[@]}"; then
                matching_packages+=("$pkg")
            fi
        done

        if [ "${#matching_packages[@]}" -eq 0 ]; then
            echo "==> mutation testing ($label) skipped: no matching packages"
            return
        fi

        for pkg in "${matching_packages[@]}"; do
            args+=(--package "$pkg")
        done
    fi

    echo "==> mutation testing ($label)"
    if [ "$extra_arg_count" -gt 0 ]; then
        cargo mutants --manifest-path "$manifest" "${args[@]}" "$@"
    else
        cargo mutants --manifest-path "$manifest" "${args[@]}"
    fi
}

run_workspace_mutants "main workspace" "$workspace_manifest" "$@"

echo "==> Results: $OUTPUT_DIR/"
