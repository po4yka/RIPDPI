---
title: Add uTLS per-connection TLS-fingerprint rotation for outbound TLS handshakes
type: task
status: doing
area: rust-native
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-16
updated: 2026-06-05
---

## Summary

Implement uTLS-style ClientHello fingerprint impersonation rotated on each outbound TLS connection. Current `ripdpi-tls-profiles` emits a single profile per session; DPI that fingerprints exact ClientHello bytes (extension order, GREASE values, cipher suite list) can correlate sessions even when domains and IPs vary.

## Context

Russian TSPU and similar nation-state DPI systems increasingly use ClientHello fingerprinting (JA3/JA4 hashes) as a high-confidence proxy-detection signal. Rotating the impersonated fingerprint per connection breaks this signal entirely.

Reference implementations: refraction-networking/utls (Go), sing-box's uTLS integration.

## Acceptance criteria

- [x] `ripdpi-tls-profiles` exposes a `RotatingProfileSelector` that picks from a pool of browser fingerprints per connection.
- [ ] Pool covers at least: Chrome 130, Firefox 125, Safari 18, iOS 18 Safari, Edge 130. (Chrome/Firefox/Safari/Edge done; **iOS 18 Safari pending an authentic ClientHello template** — see below.)
- [ ] Each TLS outbound (VLESS, xHTTP, ShadowTLS) accepts a `Profile::Rotating` config option that consults the selector. (selector mechanism exists; per-protocol call-site wiring still pending.)
- [x] Unit tests assert: every connection consumes a fresh profile; profile distribution is roughly uniform over 1000 trials.
- [x] Telemetry counter `tls.fingerprint_rotation_active` increments on each rotated handshake. (crate-local `AtomicU64` + `fingerprint_rotation_count()` + tracing event; surfacing through the Android telemetry ring still pending.)
- [x] Documentation under `docs/native/` explains the threat model and pool composition.

## Risks / open questions

- BoringSSL's ClientHello composition is opinionated; per-profile overrides may require digging into `SSL_set_quic_use_legacy_codepoint`- style hooks for less-common extensions.
- Pool maintenance: browser fingerprints rotate with major releases. Pair reviews with the upstream-spec-watch cadence in `docs/native/upstream-spec-watch-runbook.md`.

## Links

- [[Epic - Control-plane hardening]]

## Work log

- 2026-06-05: Rotation core (select_rotated_profile, select_profile_for_connection) exists in ripdpi-tls-profiles/src/rotation.rs; pool has chrome/firefox/safari/edge but no iOS 18 Safari profile; no RotatingProfileSelector struct, no Profile::Rotating config option, no tls.fingerprint_rotation_active telemetry counter, no distribution/uniformity tests over 1000 trials, no docs/native/ threat-model doc — all 6 acceptance criteria remain unmet.
- 2026-06-05: Implemented the verifiable core — `RotatingProfileSelector` (default uniform pool over chrome/firefox/safari/edge), deterministic-per-`(authority, seed)` selection, `tls.fingerprint_rotation_active` counter (`fingerprint_rotation_count()`) + tracing event, and 6 selector tests incl. the 1000-trial uniformity + per-selection counter assertions (47 tests pass, clippy clean). Added `docs/native/tls-fingerprint-rotation.md`. **Remaining (kept `doing`):** (2) an *authentic* iOS 18 Safari ClientHello template — deliberately NOT fabricated, since a wrong JA3/JA4 is itself a detection signal; gated on refraction-networking/utls reference data. (3) wire a `"rotating"` profile option through the VLESS / xHTTP / ShadowTLS outbound call sites (pass a fresh session seed per connection) + surface the counter through the Android telemetry ring.
- 2026-06-05: Re-verified all criteria. Criteria 1 (`RotatingProfileSelector` in `ripdpi-tls-profiles/src/rotation.rs`), 4 (1000-trial uniformity test + per-selection counter test in `rotation::selector_tests`), 5 (crate-local `FINGERPRINT_ROTATION_ACTIVE` `AtomicU64` + `fingerprint_rotation_count()` + `tracing::debug!` event — Android telemetry ring surfacing still absent, confirmed by search of app/src and core/), and 6 (`docs/native/tls-fingerprint-rotation.md` with threat model + pool table) remain correctly marked `[x]`. Criteria 2 (no iOS 18 Safari in `DEFAULT_ROTATION_POOL`, confirmed by comment in rotation.rs) and 3 (no `Profile::Rotating` config option, no call-site wiring in ripdpi-relay-tls-transports/ripdpi-shadowtls/ripdpi-anytls) remain `[ ]`. Status `doing` is correct; no changes to checkbox state.
