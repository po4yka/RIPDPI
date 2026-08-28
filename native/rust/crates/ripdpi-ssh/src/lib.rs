#![forbid(unsafe_code)]

//! SSH outbound engine for RIPDPI.
//!
//! SSH ([RFC 4254](https://www.rfc-editor.org/rfc/rfc4254) `direct-tcpip`
//! channels) can tunnel arbitrary TCP through an SSH server, which makes it a
//! useful censorship-evasion relay: the outer transport is an ordinary SSH
//! session that most DPI permits. This crate provides the configuration,
//! validation, secret redaction, host-key TOFU policy evaluation, and a real
//! [`russh`]-backed client engine.
//!
//! ## Outbound socket safety
//!
//! A real SSH engine opens a **non-loopback outbound TCP socket** to the SSH
//! server. `.claude/rules/vpnservice-protect-invariant.md` requires every such
//! socket to bypass the VPN's own TUN device, or the kernel routes the SSH
//! transport back into the tunnel — an infinite packet loop.
//!
//! The relay runtime carries an explicit socket-protection policy. SSH creates
//! and protects its TCP socket before passing it to `russh::client::connect_stream`;
//! VPN-required mode fails closed if protection is unavailable or rejected,
//! while the public `connect` entry point selects the inactive policy.
//!
//! ## Engine shape
//!
//! [`connect`] validates configuration and returns an owned pending [`SshClient`]
//! synchronously. [`SshClient::ready`] awaits host-key verification and password
//! or private-key authentication. The factory retains this owner before awaiting
//! readiness, including failed or cancelled construction. [`SshClient::tcp_connect`]
//! opens `direct-tcpip` channels; [`SshClient::close`] cancels transport I/O and joins
//! construction and session work. Drop signals cancellation only; it is not proof
//! of completed shutdown. The relay descriptor uses one session per flow.
//!
//! ## Prerelease crypto in the auth path
//!
//! `russh` 0.62.7 pulls **prerelease crypto** into the SSH authentication path:
//! `rsa 0.10.0-rc`, `ed25519-dalek 3.0-pre`, `ssh-key 0.7-rc`, `p521 0.14-rc`,
//! and `ml-kem`. These `-rc` / `-pre` releases have not gone through a stable
//! release's audit/freeze cycle. RIPDPI ships them because no `russh` release
//! without prerelease transitive crypto exists yet; the `rsa` exposure
//! specifically is documented as not-practically-exploitable in
//! `native/rust/deny.toml` (RUSTSEC-2023-0071), because SSH publickey auth signs
//! the session identifier rather than attacker-chosen plaintext.
//!
//! TODO(author): revisit and bump to stable-deps once `russh` ships a release
//! without `-rc` / `-pre` transitive crypto (rsa, ed25519-dalek, ssh-key, p521,
//! ml-kem). Tracking: epic-extended-outbound-protocol-support.

mod client;
mod config;
mod error;
mod probe;

pub use client::*;
pub use config::*;
pub use error::*;
pub use probe::*;
