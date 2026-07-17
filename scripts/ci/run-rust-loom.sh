#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"

loom_packages=()
while IFS= read -r package; do
  loom_packages+=("$package")
done < <(
  cargo metadata --locked --manifest-path "$workspace_manifest" --format-version 1 --no-deps |
    jq -r '.packages[] | select(.features.loom != null) | .name'
)

if [[ "${#loom_packages[@]}" -eq 0 ]]; then
  echo "error: no workspace packages expose the loom feature" >&2
  exit 1
fi

args=()
for package in "${loom_packages[@]}"; do
  args+=(--package "$package")
done

echo "==> loom packages: ${loom_packages[*]}"
(
  cd "$repo_root/native/rust"
  cargo test --locked "${args[@]}" --features loom -- loom
)
