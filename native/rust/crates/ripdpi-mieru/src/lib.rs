#![forbid(unsafe_code)]

//! Mieru outbound client for RIPDPI — **TCP carrier**.
//!
//! Mieru ([enfein/mieru](https://github.com/enfein/mieru)) tunnels a proxy
//! session over a custom carrier with **time-rotated keys for replay
//! protection**: the AEAD key is derived from `(username, password)` and a
//! 2-minute `timeSalt`, so a captured packet cannot be replayed in a later
//! window. This crate implements the **TCP carrier** end to end:
//!
//! - [`cipher`]: XChaCha20-Poly1305 with `PBKDF2-HMAC-SHA256` time-rotated keys
//!   and the user-stamped, incrementing nonce.
//! - [`metadata`] / [`segment`]: the byte-exact 32-byte metadata headers and the
//!   AEAD-sealed, padded segment frame (nonce once per direction on TCP).
//! - [`session`] / [`client`]: the open-session handshake, the in-tunnel SOCKS5
//!   connect, and a [`MieruClient`] that presents a tunnelled target as a plain
//!   duplex stream.
//!
//! See `PROTOCOL.md` for the byte-exact wire contract and its upstream
//! citations. The **UDP carrier** (KCP-like reliable ARQ) is out of scope and
//! returns [`MieruError::UdpUnsupported`].
//!
//! The replay clock comes from a caller-supplied [`ripdpi_network_time::NetworkTimeProvider`],
//! never a direct `SystemTime::now()` read; the session also calibrates that
//! provider from the server's own authenticated segment timestamps so later
//! sessions are immune to a wrong device clock. The transport is supplied
//! already-protected by the relay layer (the engine never opens a raw socket),
//! keeping the `VpnService.protect()` invariant intact.
//!
//! **Verification status.** Cipher/codec primitives are covered by deterministic
//! unit vectors and in-crate loopback tests. The pinned upstream oracle in
//! `scripts/tests/run-outbound-interop.py` exercises TCP carrier authentication,
//! SOCKS CONNECT, payload exchange and multiplexing against upstream Go code.

mod cipher;
mod client;
mod config;
mod error;
mod metadata;
mod mux;
mod owned_tasks;
mod segment;
mod session;
mod stream;

pub use client::*;
pub use config::*;
pub use error::*;
pub use mux::MieruMuxConnection;
pub use stream::MieruStream;

#[cfg(test)]
mod loopback;
