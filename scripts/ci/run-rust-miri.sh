#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
native_root="$repo_root/native/rust"

export MIRIFLAGS="${MIRIFLAGS:--Zmiri-strict-provenance}"

rustup component add --toolchain nightly miri rust-src >/dev/null

cd "$native_root"
cargo +nightly miri setup >/dev/null
cargo +nightly miri test -p ripdpi-root-helper-protocol read_unaligned_raw_fd

# Issue #15/#16/#17/#18/#19 unsafe-boundary regression suite:
# the `scoped_handle::tests` module covers `Box::into_raw`/`from_raw`
# FFI ownership transfer, `Vec::with_capacity` + `spare_capacity_mut` +
# `set_len` raw-buffer initialisation, invalid-UTF-8 input rejection,
# and the panic-unwind-still-frees discipline. All 10 tests must
# pass under Miri so the workspace's recommended unsafe-buffer
# initialisation idioms are exercised against the strict-provenance
# borrow-stacked machine.
cargo +nightly miri test -p ripdpi-vless scoped_handle
