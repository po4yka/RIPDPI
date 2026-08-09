---
id: OUT-1786264762917513
title: Complete Mieru UDP carrier and upstream interoperability
kind: feature
status: todo
area: outbound
priority: high
owner: Outbound protocol maintainer
parent: EPC-1786264762917457
blocked_by: []
spec_mode: required
openspec_change: out-1786264762917513-add-mieru-outbound-client-crate-and-profile-editor
created: 2026-04-24
updated: 2026-08-09
---

## Summary

Finish the two observable gaps in the existing Mieru implementation: the UI-selectable UDP carrier currently returns `MieruError::UdpUnsupported`, and the TCP/mux implementation has no recorded interoperability run against upstream `mita`.

## Context

Mieru uses a custom UDP-based protocol with replay resistance; the Go reference implementation is the canonical spec. Upstream tests are the reference for protocol-level correctness. TCP transport mode is also supported upstream; both should land.

## Acceptance criteria

- [ ] The implemented TCP/mux carrier is verified against a pinned upstream `mita` server; current deterministic loopback proof is preserved but not presented as upstream interoperability.
- [ ] The UI-selectable UDP carrier is implemented with upstream-compatible reliable transport, or UDP is removed from the selectable public profile contract.
- [x] Multiplexing implemented for `low`/`middle`/`high` (`mux.rs`): many `sessionID`-tagged sub-sessions share one carrier. A single serialized `Encryptor` keeps the per-direction nonce monotonic (nonce-reuse-safe under concurrent streams); a single reader task demuxes inbound segments to per-sub-session mailboxes by `sessionID` (no cross-contamination). Level → per-carrier concurrent-stream ceiling (`off`=1/`low`=8/`middle`=32/`high`=128 — RIPDPI policy, `PROTOCOL.md` §7) with backpressure; `off` keeps the one-stream-per-carrier path. The facade marks the carrier `reusable` when multiplexed so the relay pool drives many `open_stream` calls. Tests: concurrent-stream isolation + sequential carrier reuse with nonce monotonicity.
- [x] `MieruProfileScreen` validates server + port, username, password, protocol mode (TCP/UDP), mTU.
- [x] The replay key comes from a shared network-time source, never a direct device-clock read. Implemented the workspace's first network-time provider (`ripdpi-network-time`: monotonic-from-anchor with device-clock fallback), wired the relay facade to it (replacing `SystemTime::now()`), and adopted it in `ripdpi-shadowsocks` SIP022 too so the pattern is shared. The engine calibrates the shared provider once per session from the server's authenticated segment timestamp. **Residual risk (documented, not deferred — `PROTOCOL.md` §6, `ripdpi-network-time` crate docs):** first contact before any calibration still uses the device clock (within the protocols' skew tolerance); no SNTP (offline/no-backend rule), so the trusted anchor comes only from servers the user already connects to.
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

- 2026-06-05: crate scaffold exists at native/rust/crates/ripdpi-mieru/ with config, validation, and password-redacted Debug; MieruProfileScreen and mieru:// URI codec are complete; actual UDP/TCP session handshake and replay protection remain stubbed returning MieruError::Unimplemented — the 3 protocol-level criteria are unmet. **[SUPERSEDED by the third 2026-06-05 entry below — the TCP carrier was implemented later that day; this entry describes the pre-implementation state only.]**
- 2026-06-05: audit — verified against source. Criteria 4/6/7 confirmed [x]: `MieruProfileScreen.kt` validates all fields, `native/rust/crates/ripdpi-mieru/src/config.rs:125` redacts password in `Debug`, `ProxyUriCodec.kt` dispatches `"mieru"` to `parseMieru()` and `ProxyProfileUriEncoder.kt` emits `encodeMieru()`. Criteria 1/2/3/5 remain [ ]: `client.rs::connect()` and `tcp_connect()` both return `MieruError::Unimplemented`; no reference handshake test vectors present; replay clock TODO is in the stub comment only. Status stays `doing`.
- 2026-06-05: implemented the real **TCP carrier** end to end (replacing the `Unimplemented` stub), faithful to the upstream enfein/mieru wire spec (transcribed into `native/rust/crates/ripdpi-mieru/PROTOCOL.md`): `cipher.rs` (XChaCha20-Poly1305 + `SHA256(pw‖0x00‖user)` → PBKDF2-HMAC-SHA256/64 time-rotated key + user-stamped little-endian-incrementing nonce, via existing `ring`/`chacha20poly1305`), `metadata.rs` (byte-exact 32-byte data/session headers), `segment.rs` (AEAD-sealed padded frame, nonce-once-per-direction, inner `[0x00][len][data][0xff]` encapsulation), `session.rs` + `client.rs` (open-session handshake, in-tunnel SOCKS5 connect, duplex pumps). Wired into the relay via `MieruSessionFactory` (dials + runs the engine; one stream per session). Verified by 27 unit tests incl. a 1 MiB round-trip through a spec-faithful in-crate loopback peer; build + clippy `-D warnings` + fmt clean; adversarial 3-lens review passed (nonce-reuse-safe). **NOT verified: on-wire interop with a real mita server (offline).** Deferred: UDP/KCP carrier, multiplexing, upstream reference vectors, network-time wiring at the relay boundary, and confirming the Mieru RelayKind is covered by the relay protect chain.
- 2026-06-11: Epic pass — added a strategy-pack Mieru compatibility hint (`StrategyPackProtocolHint` `mieru` entry in the bundled `catalog.json`: TCP-carrier-only, UDP/mux deferred), load-bearing via `StrategyPackSnapshot.protocolHints` / `hintForProtocol` (commit `d9cb78a8`, +tests). Confirmed the remaining open criteria stay **gated/deferred**: UDP carrier (`MieruError::UdpUnsupported`) and multiplexing are the user-sanctioned gate; network-time wiring is deferred (no canonical workspace provider exists; matches the `ripdpi-shadowsocks` wall-clock posture); upstream/live `mita` interop is offline-infeasible. Status stays `doing`.
- 2026-06-14: last-mile pass — closed the two remaining design criteria (network-time + multiplexing) on `worktree-mieru-last-mile`. (1) **Network-time provider**: introduced `ripdpi-network-time` (new crate) — a monotonic-from-anchor clock with device-clock fallback and a process-wide `shared()` instance; wired Mieru's relay facade to it (replacing `SystemTime::now()`), made the engine calibrate it once per session from the server's authenticated segment timestamp, and adopted it in `ripdpi-shadowsocks` SIP022 (read + calibrate-from-UDP) so the pattern is shared, not one-off. Residual (first-contact device-clock dependence; no SNTP per no-backend rule) documented in `PROTOCOL.md` §6 + crate docs. (2) **Multiplexing**: `mux.rs` multiplexes many `sessionID`-tagged sub-sessions over one carrier — single serialized `Encryptor` (nonce-reuse-safe), single reader task demuxing by `sessionID` (no cross-contamination), level→per-carrier concurrent-stream ceiling with backpressure; facade marks the carrier reusable when multiplexed. Found+fixed a carrier-leak: the reader task is now aborted on `Drop` so the carrier closes. Atomic commits: network-time provider → mieru wiring → shadowsocks adoption → mux. **Verification tier:** `cargo nextest -p ripdpi-mieru` green (30 tests incl. primitive vectors, 1 MiB loopback round-trip + calibration assertion, concurrent-stream isolation, sequential reuse + nonce monotonicity); `ripdpi-network-time` 6 tests; clippy `-D warnings` + fmt clean on the touched crates. **Still NOT verified: on-wire interop with a real upstream `mita` server** (offline-infeasible; no live-interop claim — containerized `mita` CI fixture remains future work). UDP/KCP carrier remains the sanctioned gate. Status stays `doing` pending live interop.
