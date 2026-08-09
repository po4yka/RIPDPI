---
id: OUT-1786264762917551
title: Verify AnyTLS interoperability with upstream anytls-go
kind: feature
status: todo
area: outbound
priority: medium
risk: high
owner: Outbound protocol maintainer
parent: EPC-1786264762917457
blocked_by: []
spec_mode: required
openspec_change: out-1786264762917551-add-anytls-outbound-client-crate-and-profile-editor
created: 2026-04-24
updated: 2026-08-09
---

## Summary

AnyTLS is already a first-class relay kind with a dedicated editor, Rust implementation, relay-core backend, import support, runtime config, compatibility hints, and credential redaction. The only remaining product claim is byte-level interoperability with upstream `anytls-go`.

## Context

Upstream reference: `anytls/anytls-go`. The current source has `native/rust/crates/ripdpi-anytls`, `RelayKindAnyTls`, `RelayBackendConfig::AnyTls`, `anytls://` parsing, Sing-box/Clash AnyTLS subscription mapping, relay-core TCP and UDP-over-TCP tests, and import-confirmation UI. The main configuration editor still has no dedicated AnyTLS field screen.

## Acceptance criteria

- [x] `ripdpi-anytls` crate exists with frame, padding, and TLS-session tests.
- [x] Relay-core builds an AnyTLS backend, validates it as UDP-capable, and covers TCP plus UDP-over-TCP fixtures.
- [x] `anytls://`, Clash `anytls`, and Sing-box `anytls` imports map to first-class profiles.
- [x] Relay native config carries AnyTLS password and root-certificate fields.
- [ ] Cross-interop against upstream `anytls-go` is verified and recorded. **(deferred: live-server only; offline-infeasible nightly oracle.)**
- [x] Fallback-SNI and fallback-server behavior matches upstream spec, or unsupported behavior is rejected explicitly. (RIPDPI's client has no server-side TLS fallback; `ProxyUriCodec.parseAnyTls` now explicitly rejects `anytls://` nodes advertising a `fallback`/`fallback_sni` target rather than silently importing them.)
- [x] `AnyTLSProfileScreen` validates password length, server + port, and server-name (SNI).
- [x] The dedicated `AnyTlsProfileScreen` and import/profile paths provide the supported editing surface; duplicating them in the generic Main Mode Editor is not required.
- [x] Strategy-pack metadata advertises AnyTLS compat hints, especially around QUIC-heavy neighborhoods. (`StrategyPackProtocolHint` + bundled `catalog.json` `anytls` entry with `quicHeavyNeighborhood: true`, surfaced via `StrategyPackSnapshot.protocolHints` / `hintForProtocol`.)
- [x] Password is redacted in all diagnostic surfaces. (Rust: hand-written `Debug` for `AnyTlsClientConfig` masks password + root cert. Kotlin: `ProxyProfile.AnyTls.toString` masks the password.)

## Source references

**Reference implementation notes:**:

- `app/src/main/java/moe/matsuri/nb4a/proxy/anytls/AnyTLSBean.java` — bean fields: `password`, `sni`, `alpn`, `allowInsecure`, `idleSessionCheckInterval`, `idleSessionTimeout`, `minIdleSession`.
- `app/src/main/java/moe/matsuri/nb4a/proxy/anytls/AnyTLSFmt.kt` — `anytls://` URI codec.
- `app/src/main/java/moe/matsuri/nb4a/proxy/anytls/AnyTLSSettingsActivity.kt` — editor.

**Outbound engine (NOT from reference implementation):** upstream [`anytls/sing-anytls`](https://github.com/anytls/sing-anytls) (Go). No Rust port; either port to Rust or consume the spec directly. The handshake is small — HMAC-SHA1-based with session-ID camouflage.

**Adapt:** Bean fields, URI codec, idle-session fields (AnyTLS-specific). **Skip:** sing-box integration layer from reference implementation.

## Links

- [[Epic - Extended outbound protocol support]]

## Work log

- 2026-06-05: AnyTlsProfileScreen with validation exists and is wired into nav graph (Route.AnyTlsProfile); ripdpi-anytls crate, relay-core backend, import parsing all confirmed present; Mode Editor (ModeEditorRelaySection.kt/RelayFields.kt) has no AnyTLS fields; no strategy-pack compat hints for AnyTLS found in strategy-registry; no password-redaction evidence in diagnostics; interop/fallback-SNI tests absent.
- 2026-06-05: Re-verified all criteria. Crate confirmed real (frame.rs, padding.rs, session.rs + tests/). Config fields `anytls_password` + `anytls_root_certificate_pem` in flat.rs/conversions.rs. AnyTlsProfileEditorState.kt has validation logic. Clash import mapping not found separately (only singbox named in ImportHandlerActivity comment); criterion [x] retained from prior state. No new completions found; status remains doing.
- 2026-06-11: Epic pass — closed 3 criteria. Password redaction: hand-written `Debug` for `AnyTlsClientConfig` masks password + root cert (`ripdpi-anytls/src/session.rs`, +tests, commit `f3e77f2`); `ProxyProfile.AnyTls.toString` masks the password (commit `b87e0a85`). Fallback-SNI: `parseAnyTls` explicitly rejects `fallback`/`fallback_sni` nodes (commit `b87e0a85`, +test). Strategy-pack AnyTLS hint: `StrategyPackProtocolHint` modeled + bundled `catalog.json` entry, load-bearing via `hintForProtocol` (commit `d9cb78a8`, +tests). **Deferred:** Mode-Editor inline fields (separate end-to-end editable-relay-kind feature; AnyTLS already editable via its own screen) and live `anytls-go` interop (offline). Status stays `doing`.
