#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
native_root="$repo_root/native/rust"

export MIRIFLAGS="${MIRIFLAGS:--Zmiri-strict-provenance}"

rustup component add --toolchain nightly miri rust-src >/dev/null

cd "$native_root"
cargo +nightly miri setup >/dev/null

# Issue #15/#16/#17/#18/#19 unsafe-boundary regression suite:
# the `scoped_handle::tests` module covers `Box::into_raw`/`from_raw`
# FFI ownership transfer, `Vec::with_capacity` + `spare_capacity_mut` +
# `set_len` raw-buffer initialisation, invalid-UTF-8 input rejection,
# and the panic-unwind-still-frees discipline. All 10 tests must
# pass under Miri so the workspace's recommended unsafe-buffer
# initialisation idioms are exercised against the strict-provenance
# borrow-stacked machine. The `miri-stubs` feature additionally
# substitutes the three BoringSSL FFI calls in reality_hook.rs with
# Miri-friendly stubs, so the Box::into_raw / RealityHookGuard
# drop / extern "C" callback panic-trap dance is also exercised
# under Miri (issue #15 / #18 production code path).
cargo +nightly miri test -p ripdpi-vless --features miri-stubs scoped_handle

# Issue #15 / #18 reality_hook regression under Miri with the
# `miri-stubs` feature substituting the three BoringSSL FFI calls.
# Exercises `install_reality_client_hello_hook` → `Box::into_raw`
# → simulated callback dispatch → `RealityHookGuard::Drop` →
# `Box::from_raw` round-trip against strict-provenance.
cargo +nightly miri test -p ripdpi-vless --features miri-stubs reality_hook

# Issue #21 zero-init validity regression: `ripdpi-privileged-ops`
# holds the workspace's only `ptr::write_bytes` site (mmap_region.rs)
# plus the `MaybeUninit<u8>` recv buffer (icmp_wrapped_udp.rs).
# Running its experimental_tier3 unit suite under Miri exercises the
# audited zero-init code paths against the strict-provenance machine
# so a future regression that swaps the u8 destination for a
# non-zero-valid element type fails CI.
cargo +nightly miri test -p ripdpi-privileged-ops experimental_tier3
