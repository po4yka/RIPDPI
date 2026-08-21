#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
native_root="$repo_root/native/rust"

export MIRIFLAGS="${MIRIFLAGS:--Zmiri-strict-provenance}"

rustup component add --toolchain nightly miri rust-src >/dev/null

cd "$native_root"
cargo +nightly miri setup >/dev/null

# Issue #15/#16/#17/#18/#19/#32/#33 unsafe-boundary regression suite:
# the `soundness-canaries` crate covers `Box::into_raw`/`from_raw`
# FFI ownership transfer, `Vec::with_capacity` + `spare_capacity_mut` +
# `set_len` raw-buffer initialisation, invalid-UTF-8 input rejection,
# the partial-initialisation guard, and the ManuallyDrop discipline
# canary. All tests must pass under Miri so the workspace's recommended
# unsafe-buffer initialisation idioms are exercised against the
# strict-provenance borrow-stacked machine. The `miri-stubs` feature
# (ripdpi-vless) additionally substitutes the three BoringSSL FFI calls
# in reality_hook.rs with Miri-friendly stubs, so the Box::into_raw /
# RealityHookGuard drop / extern "C" callback panic-trap dance is also
# exercised under Miri (issue #15 / #18 production code path).
cargo +nightly miri test --locked -p soundness-canaries

# Issue #15 / #18 reality_hook regression under Miri with the
# `miri-stubs` feature substituting the three BoringSSL FFI calls.
# Exercises `install_reality_client_hello_hook` → `Box::into_raw`
# → simulated callback dispatch → `RealityHookGuard::Drop` →
# `Box::from_raw` round-trip against strict-provenance.
cargo +nightly miri test --locked -p ripdpi-vless --features miri-stubs reality_hook

# Issue #21 zero-init validity regression: `ripdpi-privileged-ops`
# holds the workspace's only `ptr::write_bytes` site (mmap_region.rs)
# plus the `MaybeUninit<u8>` recv buffer (icmp_wrapped_udp.rs).
# Running its experimental_tier3 unit suite under Miri exercises the
# audited zero-init code paths against the strict-provenance machine
# so a future regression that swaps the u8 destination for a
# non-zero-valid element type fails CI.
cargo +nightly miri test --locked -p ripdpi-privileged-ops experimental_tier3

# TODO(M9): extend Miri coverage toward the full unsafe-gap set.
#
# The `llm-rust-prompts.md` CI-infrastructure rule asks for Miri on
# every crate without `#![forbid(unsafe_code)]`. We currently exercise
# only `ripdpi-vless` (FFI-stubbed via `--features miri-stubs`) and
# `ripdpi-privileged-ops` (pure ptr/buffer arithmetic that runs as-is).
# ~19 workspace crates carry real `unsafe`; the gap set below is
# triaged by Miri-eligibility. Add a crate here ONLY after confirming
# its targeted test runs green under Miri locally — Miri has no libc /
# syscall / JNI shim, so any test that reaches a live OS resource will
# abort with "unsupported operation", which would break this CI gate.
#
# Miri-tractable AS-IS (pure pointer / buffer / Send+Sync soundness;
# audit-then-add, no FFI on the test path):
#   - ripdpi-root-helper-protocol  scm_rights.rs raw-fd ownership; the
#       OwnedFd::from_raw_fd / Box round-trip is tractable IF the
#       libc::close + socketpair calls are gated behind a stub like
#       ripdpi-vless's `miri-stubs`. Add the feature first.
#   - ripdpi-io-uring              `unsafe impl Send/Sync` for
#       RegisteredBufferPool — Stacked-Borrows/aliasing of the buffer
#       pool is tractable, but only for tests that do NOT submit to a
#       real ring (io_uring syscalls are unsupported under Miri).
#   - ripdpi-protocol-detect / ripdpi-geo  small slice/transmute-class
#       unsafe with no syscall surface — best low-risk next additions.
#
# Needs an FFI-stub feature BEFORE it can be added (mirror the
# `miri-stubs` pattern: substitute the syscall/JNI calls with
# Miri-friendly stand-ins, exercise only the unsafe ownership dance):
#   - ripdpi-runtime-platform      io_uring / protect / process / socket
#       all reach libc + netlink + JNI.
#   - ripdpi-tunnel-core           AsyncDevice::from_fd takes a live
#       kernel TUN fd; io_loop.rs shares raw fds.
#   - ripdpi-root-helper           main.rs / dispatch.rs syscall-heavy.
#   - ripdpi-proxy-runtime(+desync-adapter), ripdpi-desync-runtime
#       socket + raw-packet paths.
#
# NOT Miri-eligible (JNI / NDK boundary — no JVM under Miri, would need
# a full mock JavaVM that defeats the purpose):
#   - ripdpi-tunnel-android, ripdpi-warp-android, ripdpi-relay-android,
#     ripdpi-android-proxy-adapter, ripdpi-android-vpn-protect-adapter,
#     ripdpi-android-bridge-support, android-support.
#
# Support/fixture crates (golden-test-support, local-network-fixture,
# native-soak-support, ripdpi-monitor-engine, ripdpi-diagnostics-transport)
# carry only incidental unsafe; cover them opportunistically when their
# owning feature crate is added, not on their own.
