#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
fuzz_dir="$repo_root/native/rust/fuzz"
host_target="$(rustc +nightly -vV | sed -n 's/^host: //p')"
scratch_root="$(mktemp -d "${TMPDIR:-/tmp}/ripdpi-fuzz-smoke.XXXXXX")"
artifacts_root="${RIPDPI_FUZZ_ARTIFACTS_DIR:-$scratch_root/artifacts}"

cleanup() {
  rm -rf "$scratch_root"
}
trap cleanup EXIT

fuzz_targets=()
while IFS= read -r target; do
  fuzz_targets+=("$target")
done < <(
  awk '
    /^\[\[bin\]\]$/ { in_bin = 1; next }
    in_bin && /^name = / {
      gsub(/^name = "|"$/, "")
      print
      in_bin = 0
    }
  ' "$fuzz_dir/Cargo.toml"
)

if ! command -v cargo >/dev/null 2>&1; then
  echo "error: cargo is required for the fuzz smoke check" >&2
  exit 1
fi

if [[ -z "$host_target" ]]; then
  echo "error: unable to determine the nightly host target for cargo-fuzz" >&2
  exit 1
fi

if [[ "${#fuzz_targets[@]}" -eq 0 ]]; then
  echo "error: no cargo-fuzz targets declared in $fuzz_dir/Cargo.toml" >&2
  exit 1
fi

mkdir -p "$artifacts_root"

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
  local scratch_artifacts="$artifacts_root/$target"
  mkdir -p "$scratch_corpus" "$scratch_artifacts"
  if [[ -d "$seed_corpus" ]]; then
    cp -R "$seed_corpus"/. "$scratch_corpus"/
  fi
  run_fuzz run "$target" "$scratch_corpus" -- -artifact_prefix="$scratch_artifacts/" "$@"
}

if [[ -n "${RIPDPI_FUZZ_SECONDS:-}" ]]; then
  for target in "${fuzz_targets[@]}"; do
    echo "==> fuzz nightly: run $target for ${RIPDPI_FUZZ_SECONDS}s"
    run_fuzz_target "$target" -max_total_time="$RIPDPI_FUZZ_SECONDS"
  done
else
  for target in "${fuzz_targets[@]}"; do
    echo "==> fuzz smoke: build $target"
    run_fuzz build "$target"
  done
  echo "==> fuzz smoke: run packets_parse once"
  run_fuzz_target packets_parse -runs=1
fi
