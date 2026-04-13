#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
fuzz_dir="$repo_root/native/rust/fuzz"
host_target="$(rustc +nightly -vV | sed -n 's/^host: //p')"

if ! command -v cargo >/dev/null 2>&1; then
  echo "error: cargo is required for the fuzz smoke check" >&2
  exit 1
fi

if [[ -z "$host_target" ]]; then
  echo "error: unable to determine the nightly host target for cargo-fuzz" >&2
  exit 1
fi

sanitizer_rustflags="${RUSTFLAGS:-}"
if [[ "$host_target" == *-musl ]]; then
  sanitizer_rustflags="${sanitizer_rustflags:+$sanitizer_rustflags }-C target-feature=-crt-static"
fi

run_fuzz() {
  local subcommand="$1"
  shift
  (
    cd "$fuzz_dir"
    env \
      -u CARGO_BUILD_TARGET \
      RUSTFLAGS="$sanitizer_rustflags" \
      cargo +nightly fuzz "$subcommand" --target "$host_target" "$@"
  )
}

echo "==> fuzz smoke: run packets_parse once"
run_fuzz run packets_parse -- -runs=1

echo "==> fuzz smoke: build packets_tls_quic"
run_fuzz build packets_tls_quic

for target in failure_http_response failure_field_cache; do
  echo "==> fuzz smoke: build $target"
  run_fuzz build "$target"
done
