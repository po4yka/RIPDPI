---
title: Add Mieru outbound client crate and profile editor
type: task
status: doing
area: outbound
priority: medium
owner: unassigned
parent: epic-extended-outbound-protocol-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-06-05
---

## Summary

Add a `ripdpi-mieru` Rust crate implementing the Mieru outbound client and a `MieruProfileScreen` editor. Mieru (enfein/mieru) is actively developed and used in the Chinese bypass community; ignoring it blocks that user cohort.

## Context

Mieru uses a custom UDP-based protocol with replay resistance; the Go reference implementation is the canonical spec. Upstream tests are the reference for protocol-level correctness. TCP transport mode is also supported upstream; both should land.

## Acceptance criteria

- [~] `ripdpi-mieru` TCP carrier is implemented (XChaCha20-Poly1305 time-rotated PBKDF2 keys, user-stamped incrementing nonce, byte-exact 32-byte metadata + segment framing, open-session handshake, in-tunnel SOCKS5 connect) and wired into the relay (`MieruSessionFactory` dials + runs it). Covered by deterministic primitive vectors + a spec-faithful in-crate loopback 1 MiB round-trip (self-consistency). **Upstream/live-server reference vectors still pending** — on-wire interop is unverified offline. See `native/rust/crates/ripdpi-mieru/PROTOCOL.md`.
- [~] TCP carrier supported; the UDP carrier (KCP-like reliable ARQ) returns `MieruError::UdpUnsupported` (out of scope, deferred).
- [ ] Multiplexing not implemented: one relayed stream per session (mux `off` semantics); the `multiplexing` knob is accepted but session reuse is not yet wired.
- [x] `MieruProfileScreen` validates server + port, username, password, protocol mode (TCP/UDP), mTU.
- [~] The engine derives the replay key from an injected `now_unix` network-time source (never the device clock), per the crate doc; the relay facade currently passes `SystemTime::now()` at the integration boundary — wiring the canonical network-time source there remains.
- [x] Credentials redacted in all diagnostic surfaces.
- [x] Subscription import path recognizes `mieru://` URIs.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/fmt/mieru/MieruBean.java` — bean fields: `username`, `password`, `mtu`, `protocol` (TCP/UDP), `multiplexing` (OFF/LOW/MIDDLE/HIGH).
- `app/src/main/java/io/nekohasekai/sagernet/ui/profile/MieruSettingsActivity.kt` — editor.
- reference implementation has no `mieru://` URI codec (editor + plugin-config-only); **RIPDPI should invent one** since subscription import is a stated goal.

**Outbound engine (NOT from reference implementation):** upstream [`enfein/mieru`](https://github.com/enfein/mieru) (Go). Reference implementation shells out to the `mieru-plugin` APK; RIPDPI needs a pure-Rust port or vendored build. The protocol is custom UDP-based with replay protection — non-trivial port effort.

**Adapt:** Bean fields, multiplexing level mapping. **Invent:** `mieru://` URI scheme (e.g. `mieru://username:password@host:port?protocol=tcp&mux=middle`). **Skip:** Reference implementation's external-process plugin path.

## Links

- [[Epic - Extended outbound protocol support]]

## Work log

- 2026-06-05: crate scaffold exists at native/rust/crates/ripdpi-mieru/ with config, validation, and password-redacted Debug; MieruProfileScreen and mieru:// URI codec are complete; actual UDP/TCP session handshake and replay protection remain stubbed returning MieruError::Unimplemented — the 3 protocol-level criteria are unmet.
- 2026-06-05: audit — verified against source. Criteria 4/6/7 confirmed [x]: `MieruProfileScreen.kt` validates all fields, `native/rust/crates/ripdpi-mieru/src/config.rs:125` redacts password in `Debug`, `ProxyUriCodec.kt` dispatches `"mieru"` to `parseMieru()` and `ProxyProfileUriEncoder.kt` emits `encodeMieru()`. Criteria 1/2/3/5 remain [ ]: `client.rs::connect()` and `tcp_connect()` both return `MieruError::Unimplemented`; no reference handshake test vectors present; replay clock TODO is in the stub comment only. Status stays `doing`.
- 2026-06-05: implemented the real **TCP carrier** end to end (replacing the `Unimplemented` stub), faithful to the upstream enfein/mieru wire spec (transcribed into `native/rust/crates/ripdpi-mieru/PROTOCOL.md`): `cipher.rs` (XChaCha20-Poly1305 + `SHA256(pw‖0x00‖user)` → PBKDF2-HMAC-SHA256/64 time-rotated key + user-stamped little-endian-incrementing nonce, via existing `ring`/`chacha20poly1305`), `metadata.rs` (byte-exact 32-byte data/session headers), `segment.rs` (AEAD-sealed padded frame, nonce-once-per-direction, inner `[0x00][len][data][0xff]` encapsulation), `session.rs` + `client.rs` (open-session handshake, in-tunnel SOCKS5 connect, duplex pumps). Wired into the relay via `MieruSessionFactory` (dials + runs the engine; one stream per session). Verified by 27 unit tests incl. a 1 MiB round-trip through a spec-faithful in-crate loopback peer; build + clippy `-D warnings` + fmt clean; adversarial 3-lens review passed (nonce-reuse-safe). **NOT verified: on-wire interop with a real mita server (offline).** Deferred: UDP/KCP carrier, multiplexing, upstream reference vectors, network-time wiring at the relay boundary, and confirming the Mieru RelayKind is covered by the relay protect chain.
