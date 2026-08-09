#!/usr/bin/env bash
set -euo pipefail

readonly zizmor_version="1.29.0"
repo_root="$(git rev-parse --show-toplevel)"
readonly repo_root
readonly -a zizmor_args=(
  --offline
  --strict-collection
  --no-progress
  --color never
  --persona regular
  --no-ignores
  .
)

command -v uvx >/dev/null || {
  echo "error: uvx is required to run pinned zizmor $zizmor_version" >&2
  exit 1
}

cd "$repo_root"
exec uvx --from "zizmor==$zizmor_version" zizmor "${zizmor_args[@]}"
