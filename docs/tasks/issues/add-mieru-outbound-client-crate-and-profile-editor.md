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

- [ ] `ripdpi-mieru` crate passes upstream reference handshake + session-framing test vectors.
- [ ] UDP and TCP transport modes both supported.
- [ ] Multiplexing behavior matches upstream.
- [x] `MieruProfileScreen` validates server + port, username, password, protocol mode (TCP/UDP), mTU.
- [ ] Mieru's time-based replay protection is clock-synced via the existing network-time source, not `System.currentTimeMillis`.
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
