#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"
byedpi_manifest="$repo_root/native/rust/third_party/byedpi/Cargo.toml"

TEST_TOOL="${MUTANTS_TEST_TOOL:-nextest}"
PACKAGES="${MUTANTS_PACKAGES:-}"
JOBS="${MUTANTS_JOBS:-auto}"

common_args=(--test-tool "$TEST_TOOL" --jobs "$JOBS" --output "$repo_root/target/mutants-output")

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
    local extra_args=("$@")
    local args=("${common_args[@]}")

    if [ -n "$PACKAGES" ]; then
        mapfile -t available_packages < <(workspace_packages "$manifest")
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
    cargo mutants --manifest-path "$manifest" "${args[@]}" "${extra_args[@]}"
}

run_workspace_mutants "main workspace" "$workspace_manifest" "$@"
run_workspace_mutants "byedpi workspace" "$byedpi_manifest" "$@"

echo "==> Results: $repo_root/target/mutants-output/"
