#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "error: this gate requires Linux and real io_uring execution" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"

# The feature test requires successful fixed-buffer CQEs and rejects fallback.
exec bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked \
  --manifest-path "$repo_root/native/rust/Cargo.toml" \
  -p ripdpi-proxy-runtime --lib --features io-uring --profile ci \
  --build-jobs 4 --test-threads 4 --retries 0 --no-tests fail
