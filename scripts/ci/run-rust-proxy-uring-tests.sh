#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "error: this gate requires Linux and real io_uring execution" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"

# The feature test requires successful fixed-buffer CQEs and rejects fallback.
# The socket-filter test needs CAP_NET_ADMIN; run-linux-bpf-tests.py runs it.
exec bash "$repo_root/scripts/ci/cargo-guarded.sh" cargo nextest run --locked \
  --manifest-path "$repo_root/native/rust/Cargo.toml" \
  -p ripdpi-proxy-runtime --lib --features io-uring --profile ci \
  -E 'not test(=runtime::routing::connect::post_connect::tests::strip_timestamps_does_not_replace_the_drop_sack_filter)' \
  --build-jobs 4 --test-threads 4 --retries 0 --no-tests fail
