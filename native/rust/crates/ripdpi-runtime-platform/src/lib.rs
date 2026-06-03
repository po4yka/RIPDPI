//! `ripdpi-runtime-platform` — the **L5 platform layer**.
//!
//! This crate is the runtime's single window onto OS-level networking. It does
//! not implement privileged syscalls itself — those live in
//! `ripdpi-privileged-ops`, `ripdpi-native-protect`, `ripdpi-io-uring`, and the
//! `ripdpi-root-helper-*` crates. What this crate adds is *adaptation*: a
//! stable, organized public surface, non-root / non-Linux fallbacks, and the
//! root-helper-vs-local dispatch every privileged operation needs.
//!
//! # Module taxonomy
//!
//! Every module falls into exactly one of three roles. Each module file
//! repeats its role in its own `//!` header.
//!
//! ## 1. Public facade modules (`pub mod`)
//!
//! Thin `pub use` aggregators — they own no logic, only organize the public
//! API into stable, named surfaces:
//!
//! - [`capability`] — runtime capability detection + process/thread helpers.
//! - [`experimental`] — tier-3 experimental raw-IP operations.
//! - [`protect`] — re-export of the `ripdpi-native-protect` VPN-protect
//!   callback registry.
//! - [`raw_packet`] — raw TCP/UDP packet emission (fake-send, IP frag).
//! - [`socket`] — socket-option syscalls.
//! - [`tcp`] — TCP introspection + retransmit-capability detection.
//! - [`ttl_ops`] — TTL read/recv operations.
//! - [`vpn`] — the `protect_socket` entry point.
//!
//! ## 2. OS-primitive adapter modules (private `mod`)
//!
//! Thin `#[cfg]`-split wrappers over a single upstream primitive crate. Each
//! has a Linux/Android arm that delegates to `ripdpi-privileged-ops` (or
//! `ripdpi-io-uring`) and a non-Linux arm with a static fallback. They hold no
//! process state and perform no root-helper dispatch:
//! `bpf_timestamp`, `capabilities`, `io_uring`, `original_destination`,
//! `process`, `retransmit`, `socket_options`, `tcp_info`, `ttl`.
//!
//! ## 3. Runtime-adaptation modules
//!
//! Modules that carry process-global state, own a registry, or choose between
//! the privileged root helper and a local path at call time:
//! `vpn_protect`, `experimental_tier3`, `fake_send`, `ip_fragmentation`,
//! `ipv4_ids`, and the `root_helper` / `root_helper_client` pair.
//!
//! # Invariants
//!
//! - **Non-root fallback.** Every privileged path probes `with_root_helper()`
//!   (or `has_protect_callback()`) first and falls back to a local
//!   non-privileged path; on non-Linux targets it degrades to a static stub.
//!   Never regress this — see the non-root baseline in `RUNTIME_MODES.md`.
//! - **JNI-free.** This crate defines the platform *port*; the Android adapters
//!   implement the JNI side. No `jni` dependency may be added here.
//! - **No policy.** Strategy / adaptive *decision* logic is L3, not here.

// Lint floor for unsafe-bearing crates (see the `rust-unsafe` skill): every
// `unsafe { ... }` block needs a preceding `// SAFETY:` note, and a block may
// not bundle multiple unsafe operations under one comment. Matches the floor
// already opted into by `ripdpi-io-uring` / `ripdpi-privileged-ops` /
// `ripdpi-proxy-runtime`.
#![warn(clippy::undocumented_unsafe_blocks)]
#![warn(clippy::multiple_unsafe_ops_per_block)]

// ===== Public facade modules — the stable API surface =====
pub mod capability;
pub mod experimental;
pub mod protect;
pub mod raw_packet;
pub mod socket;
pub mod tcp;
pub mod ttl_ops;
pub mod vpn;

// ===== OS-primitive adapter modules (private) — thin syscall wrappers =====
mod bpf_timestamp;
mod capabilities;
#[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
mod io_uring;
mod original_destination;
mod process;
mod retransmit;
mod socket_options;
mod tcp_info;
mod ttl;

// ===== Runtime-adaptation modules — process state + root-helper dispatch =====
mod experimental_tier3;
mod fake_send;
mod ip_fragmentation;
mod ipv4_ids;
mod vpn_protect;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub mod root_helper;
#[cfg(any(target_os = "linux", target_os = "android"))]
pub mod root_helper_client;

#[cfg(test)]
mod tests;

// Privileged-ops value types reused across this crate's modules. Re-exported
// at `pub(crate)` only — the public surface exposes them through the facade
// modules (`raw_packet`, `tcp`) that actually use them.
pub(crate) use ripdpi_privileged_ops::{
    FakeTcpOptions, IpFragmentationCapabilities, OrderedTcpSegment, TcpActivationState, TcpFlagOverrides,
    TcpPayloadSegment, TcpStageWait,
};
