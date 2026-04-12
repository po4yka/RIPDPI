#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
native_root="$repo_root/native/rust"

export MIRIFLAGS="${MIRIFLAGS:--Zmiri-strict-provenance}"

rustup component add --toolchain nightly miri rust-src >/dev/null

cd "$native_root"
cargo +nightly miri setup >/dev/null
cargo +nightly miri test -p ripdpi-runtime read_unaligned_raw_fd
