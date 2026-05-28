---
title: Finish AnyTLS profile editor and compatibility gaps
type: task
status: backlog
area: outbound
priority: medium
owner: unassigned
parent: epic-extended-outbound-protocol-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-28
---

- [ ] #task Finish AnyTLS profile editor and compatibility gaps #repo/RIPDPI #area/outbound #status/backlog 🔼

## Summary

AnyTLS is now a first-class relay kind with a Rust crate, relay-core backend, URI/subscription import support, and runtime config fields. Keep this task for the remaining UI and compatibility polish that is not yet present in the codebase.

## Context

Upstream reference: `anytls/anytls-go`. The current source has `native/rust/crates/ripdpi-anytls`, `RelayKindAnyTls`, `RelayBackendConfig::AnyTls`, `anytls://` parsing, Sing-box/Clash AnyTLS subscription mapping, relay-core TCP and UDP-over-TCP tests, and import-confirmation UI. The main configuration editor still has no dedicated AnyTLS field screen.

## Acceptance criteria

- [x] `ripdpi-anytls` crate exists with frame, padding, and TLS-session tests.
- [x] Relay-core builds an AnyTLS backend, validates it as UDP-capable, and covers TCP plus UDP-over-TCP fixtures.
- [x] `anytls://`, Clash `anytls`, and Sing-box `anytls` imports map to first-class profiles.
- [x] Relay native config carries AnyTLS password and root-certificate fields.
- [ ] Cross-interop against upstream `anytls-go` is verified and recorded.
- [ ] Fallback-SNI and fallback-server behavior matches upstream spec, or unsupported behavior is rejected explicitly.
- [ ] `AnyTLSProfileScreen` validates password length, server + port, and server-name (SNI).
- [ ] Main Mode Editor exposes AnyTLS fields instead of relying only on import/profile records.
- [ ] Strategy-pack metadata advertises AnyTLS compat hints, especially around QUIC-heavy neighborhoods.
- [ ] Password is redacted in all diagnostic surfaces.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/moe/matsuri/nb4a/proxy/anytls/AnyTLSBean.java` — bean fields: `password`, `sni`, `alpn`, `allowInsecure`, `idleSessionCheckInterval`, `idleSessionTimeout`, `minIdleSession`.
- `app/src/main/java/moe/matsuri/nb4a/proxy/anytls/AnyTLSFmt.kt` — `anytls://` URI codec.
- `app/src/main/java/moe/matsuri/nb4a/proxy/anytls/AnyTLSSettingsActivity.kt` — editor.

**Outbound engine (NOT from reference implementation):** upstream [`anytls/sing-anytls`](https://github.com/anytls/sing-anytls) (Go). No Rust port; either port to Rust or consume the spec directly. The handshake is small — HMAC-SHA1-based with session-ID camouflage.

**Adapt:** Bean fields, URI codec, idle-session fields (AnyTLS-specific). **Skip:** sing-box integration layer from reference implementation.

## Links

- [[Epic - Extended outbound protocol support]]
