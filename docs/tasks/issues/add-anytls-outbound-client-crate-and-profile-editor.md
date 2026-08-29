---
id: OUT-1786264762917551
title: Finish AnyTLS profile editor and compatibility gaps
kind: feature
status: done
area: outbound
priority: medium
owner: unassigned
parent: EPC-1786264762917457
blocked_by: []
spec_mode: required
openspec_change: out-1786264762917551-add-anytls-outbound-client-crate-and-profile-editor
created: 2026-04-24
updated: 2026-08-29
closed_at: "2026-08-29T13:55:08Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Pinned anytls-go interop, Mode Editor persistence/validation and redaction are implemented; targeted local gates and exact-SHA CI 33251657196 passed.
---

## Summary

AnyTLS is now a first-class relay kind with a Rust crate, relay-core backend, URI/subscription import support, runtime config fields, upstream interop, and a dedicated Mode Editor surface.

## Context

Upstream reference: `anytls/anytls-go`. The current source has `native/rust/crates/ripdpi-anytls`, `RelayKindAnyTls`, `RelayBackendConfig::AnyTls`, `anytls://` parsing, Sing-box/Clash AnyTLS subscription mapping, relay-core TCP and UDP-over-TCP tests, import-confirmation UI, and Mode Editor persistence.

## Acceptance criteria

- [x] `ripdpi-anytls` crate exists with frame, padding, and TLS-session tests.
- [x] Relay-core builds an AnyTLS backend, validates it as UDP-capable, and covers TCP plus UDP-over-TCP fixtures.
- [x] `anytls://`, Clash `anytls`, and Sing-box `anytls` imports map to first-class profiles.
- [x] Relay native config carries AnyTLS password and root-certificate fields.
- [x] Cross-interop against upstream `anytls-go` is verified and recorded.
- [x] Fallback-SNI and fallback-server behavior matches upstream spec, or unsupported behavior is rejected explicitly.
- [x] `AnyTLSProfileScreen` validates password length, server + port, and server-name (SNI).
- [x] Main Mode Editor exposes AnyTLS fields instead of relying only on import/profile records.
- [x] Strategy-pack metadata advertises AnyTLS compat hints, especially around QUIC-heavy neighborhoods.
- [x] Password is redacted in all diagnostic surfaces.

## Source references

- `app/src/main/java/moe/matsuri/nb4a/proxy/anytls/AnyTLSBean.java` — reference bean fields.
- `app/src/main/java/moe/matsuri/nb4a/proxy/anytls/AnyTLSFmt.kt` — reference URI codec.
- `app/src/main/java/moe/matsuri/nb4a/proxy/anytls/AnyTLSSettingsActivity.kt` — reference editor.
- Upstream [`anytls/sing-anytls`](https://github.com/anytls/sing-anytls) protocol implementation.

## Links

- [[Epic - Extended outbound protocol support]]

## Work log

- 2026-06-05: Existing AnyTLS crate, relay backend, import parsing and dedicated profile screen confirmed; Mode Editor, compatibility hints, complete redaction and upstream interop remained open.
- 2026-06-11: Rust and Kotlin redaction, explicit fallback rejection, and strategy-pack compatibility hints completed.
- 2026-08-28: `4d6f848bc` verified pinned `anytls-go` interoperability for 64 KiB TCP, sibling stream closure, UDP-over-TCP sizes 0/1/1200/8192 and wrong-password rejection. `4fddd82ec` added masked AnyTLS password, SNI and UDP controls to the main Mode Editor with identity-safe credential persistence. Targeted gates and exact-SHA hosted CI passed.
- 2026-08-29: Reintroduced as a committed review snapshot to repair the published deletion history after CI correctly rejected the earlier direct `doing` to `done` closure.
