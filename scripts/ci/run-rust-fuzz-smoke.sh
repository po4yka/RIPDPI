#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
fuzz_dir="$repo_root/native/rust/fuzz"
host_target="$(rustc +nightly -vV | sed -n 's/^host: //p')"
scratch_root="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-fuzz-smoke.XXXXXX")"

cleanup() {
  rm -rf "$scratch_root"
}
trap cleanup EXIT

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

run_fuzz_target() {
  local target="$1"
  shift
  local seed_corpus="$fuzz_dir/corpus/$target"
  local scratch_corpus="$scratch_root/corpus/$target"
  local scratch_artifacts="$scratch_root/artifacts/$target"
  mkdir -p "$scratch_corpus" "$scratch_artifacts"
  if [[ -d "$seed_corpus" ]]; then
    cp -R "$seed_corpus"/. "$scratch_corpus"/
  fi
  run_fuzz run "$target" "$scratch_corpus" -- -artifact_prefix="$scratch_artifacts/" "$@"
}

if [[ -n "${RIPDPI_FUZZ_SECONDS:-}" ]]; then
  for target in packets_parse packets_tls_quic failure_http_response failure_field_cache; do
    echo "==> fuzz nightly: run $target for ${RIPDPI_FUZZ_SECONDS}s"
    run_fuzz_target "$target" -max_total_time="$RIPDPI_FUZZ_SECONDS"
  done
else
  echo "==> fuzz smoke: run packets_parse once"
  run_fuzz_target packets_parse -runs=1
  echo "==> fuzz smoke: build packets_tls_quic"
  run_fuzz build packets_tls_quic
  for target in failure_http_response failure_field_cache; do
    echo "==> fuzz smoke: build $target"
    run_fuzz build "$target"
  done
fi
