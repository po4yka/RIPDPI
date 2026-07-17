#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"

TEST_TOOL="${MUTANTS_TEST_TOOL:-nextest}"
PACKAGES="${MUTANTS_PACKAGES:-}"
JOBS="${MUTANTS_JOBS:-}"
SHARD_COUNT="${MUTANTS_SHARD_COUNT:-1}"
SHARD_INDEX="${MUTANTS_SHARD_INDEX:-0}"
OUTPUT_DIR="${MUTANTS_OUTPUT_DIR:-$repo_root/target/mutants-output}"
TMP_DIFF_DIR=""

mkdir -p "$(dirname "$OUTPUT_DIR")"
export RIPDPI_REPO_ROOT="${RIPDPI_REPO_ROOT:-$repo_root}"
export RIPDPI_CONTRACT_FIXTURES_DIR="${RIPDPI_CONTRACT_FIXTURES_DIR:-$RIPDPI_REPO_ROOT/contract-fixtures}"

cleanup() {
    if [ -n "$TMP_DIFF_DIR" ]; then
        rm -rf "$TMP_DIFF_DIR"
    fi
}
trap cleanup EXIT

common_args=(--test-tool "$TEST_TOOL" --output "$OUTPUT_DIR")
if [ -n "$JOBS" ]; then
    if [[ ! "$JOBS" =~ ^[0-9]+$ ]]; then
        echo "error: MUTANTS_JOBS must be a numeric cargo-mutants --jobs value, got: $JOBS" >&2
        exit 2
    fi
    common_args+=(--jobs "$JOBS")
fi

if [[ ! "$SHARD_COUNT" =~ ^[1-9][0-9]*$ ]]; then
    echo "error: MUTANTS_SHARD_COUNT must be a positive integer, got: $SHARD_COUNT" >&2
    exit 2
fi
if [[ ! "$SHARD_INDEX" =~ ^[0-9]+$ ]] || [ "$SHARD_INDEX" -ge "$SHARD_COUNT" ]; then
    echo "error: MUTANTS_SHARD_INDEX must be an integer in 0..$((SHARD_COUNT - 1)), got: $SHARD_INDEX" >&2
    exit 2
fi

workspace_packages() {
    local manifest="$1"
    cargo metadata --locked --manifest-path "$manifest" --format-version 1 --no-deps | jq -r '.packages[].name'
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

resolve_package_scope() {
    local manifest="$1"
    local available_packages=()
    local pkg
    while IFS= read -r pkg; do
        available_packages+=("$pkg")
    done < <(workspace_packages "$manifest" | sort)

    resolved_packages=()
    if [ -n "$PACKAGES" ]; then
        for pkg in $PACKAGES; do
            if package_belongs_to_workspace "$pkg" "${available_packages[@]}"; then
                resolved_packages+=("$pkg")
            fi
        done
        return
    fi

    if [ "$SHARD_COUNT" -eq 1 ]; then
        return
    fi

    for pkg in "${available_packages[@]}"; do
        local checksum
        checksum="$(printf '%s' "$pkg" | cksum | awk '{print $1}')"
        if [ "$((checksum % SHARD_COUNT))" -eq "$SHARD_INDEX" ]; then
            resolved_packages+=("$pkg")
        fi
    done
}

normalize_extra_args() {
    local manifest="$1"
    shift
    normalized_extra_args=()

    while [ "$#" -gt 0 ]; do
        local arg="$1"
        shift
        if [ "$arg" != "--in-diff" ]; then
            normalized_extra_args+=("$arg")
            continue
        fi
        if [ "$#" -eq 0 ]; then
            echo "error: --in-diff requires a diff file path or git diff command" >&2
            exit 2
        fi

        local diff_source="$1"
        shift
        if [ -f "$diff_source" ]; then
            normalized_extra_args+=(--in-diff "$diff_source")
            continue
        fi
        if [[ "$diff_source" != git\ diff* ]]; then
            echo "error: --in-diff must be a readable diff file or a git diff command, got: $diff_source" >&2
            exit 2
        fi

        if [ -z "$TMP_DIFF_DIR" ]; then
            TMP_DIFF_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-mutants-diff.XXXXXX")"
        fi
        local workspace_dir="${manifest%/*}"
        local workspace_rel="${workspace_dir#$repo_root/}"
        local diff_file="$TMP_DIFF_DIR/$(basename "$workspace_dir").diff"
        local diff_args_text="${diff_source#git diff}"
        # shellcheck disable=SC2206
        local diff_args=($diff_args_text)
        (cd "$repo_root" && git diff --relative="$workspace_rel" "${diff_args[@]}") >"$diff_file"
        normalized_extra_args+=(--in-diff "$diff_file")
    done
}

run_workspace_mutants() {
    local label="$1"
    local manifest="$2"
    shift 2
    normalize_extra_args "$manifest" "$@"
    local extra_arg_count="${#normalized_extra_args[@]}"
    local args=("${common_args[@]}")

    resolve_package_scope "$manifest"
    if [ -n "$PACKAGES" ] || [ "$SHARD_COUNT" -gt 1 ]; then
        if [ "${#resolved_packages[@]}" -eq 0 ]; then
            echo "==> mutation testing ($label) skipped: no packages selected"
            return
        fi

        for pkg in "${resolved_packages[@]}"; do
            args+=(--package "$pkg")
        done
    fi

    if [ -n "$PACKAGES" ]; then
        echo "==> mutation testing ($label): requested packages ${resolved_packages[*]}"
    elif [ "$SHARD_COUNT" -gt 1 ]; then
        echo "==> mutation testing ($label): shard $SHARD_INDEX/$SHARD_COUNT (${resolved_packages[*]})"
    else
        echo "==> mutation testing ($label): full workspace"
    fi
    if [ "$extra_arg_count" -gt 0 ]; then
        cargo mutants --manifest-path "$manifest" "${args[@]}" "${normalized_extra_args[@]}"
    else
        cargo mutants --manifest-path "$manifest" "${args[@]}"
    fi
}

run_workspace_mutants "main workspace" "$workspace_manifest" "$@"

echo "==> Results: $OUTPUT_DIR/"
