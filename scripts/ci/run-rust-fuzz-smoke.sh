#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
fuzz_dir="$repo_root/native/rust/fuzz"

if ! command -v cargo >/dev/null 2>&1; then
  echo "error: cargo is required for the fuzz smoke check" >&2
  exit 1
fi

echo "==> fuzz smoke: run packets_parse once"
(
  cd "$fuzz_dir"
  cargo +nightly fuzz run packets_parse -- -runs=1
)

for target in failure_http_response failure_field_cache; do
  echo "==> fuzz smoke: build $target"
  (
    cd "$fuzz_dir"
    cargo +nightly fuzz build "$target"
  )
done
