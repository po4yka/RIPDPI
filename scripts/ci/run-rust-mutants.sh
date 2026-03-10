#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"
byedpi_manifest="$repo_root/native/rust/third_party/byedpi/Cargo.toml"

TEST_TOOL="${MUTANTS_TEST_TOOL:-nextest}"
PACKAGES="${MUTANTS_PACKAGES:-}"
JOBS="${MUTANTS_JOBS:-auto}"

common_args=(--test-tool "$TEST_TOOL" --jobs "$JOBS" --output "$repo_root/target/mutants-output")

if [ -n "$PACKAGES" ]; then
    for pkg in $PACKAGES; do
        common_args+=(--package "$pkg")
    done
fi

echo "==> mutation testing (main workspace)"
cargo mutants --manifest-path "$workspace_manifest" "${common_args[@]}" "$@"

echo "==> mutation testing (byedpi workspace)"
cargo mutants --manifest-path "$byedpi_manifest" "${common_args[@]}" "$@"

echo "==> Results: $repo_root/target/mutants-output/"
