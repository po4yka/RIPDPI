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
updated: 2026-05-31
---

## Summary

Implement uTLS-style ClientHello fingerprint impersonation rotated on each outbound TLS connection. Current `ripdpi-tls-profiles` emits a single profile per session; DPI that fingerprints exact ClientHello bytes (extension order, GREASE values, cipher suite list) can correlate sessions even when domains and IPs vary.

## Context

Russian TSPU and similar nation-state DPI systems increasingly use ClientHello fingerprinting (JA3/JA4 hashes) as a high-confidence proxy-detection signal. Rotating the impersonated fingerprint per connection breaks this signal entirely.

Reference implementations: refraction-networking/utls (Go), sing-box's uTLS integration.

## Acceptance criteria

- [ ] `ripdpi-tls-profiles` exposes a `RotatingProfileSelector` that picks from a pool of browser fingerprints per connection.
- [ ] Pool covers at least: Chrome 130, Firefox 125, Safari 18, iOS 18 Safari, Edge 130.
- [ ] Each TLS outbound (VLESS, xHTTP, ShadowTLS) accepts a `Profile::Rotating` config option that consults the selector.
- [ ] Unit tests assert: every connection consumes a fresh profile; profile distribution is roughly uniform over 1000 trials.
- [ ] Telemetry counter `tls.fingerprint_rotation_active` increments on each rotated handshake.
- [ ] Documentation under `docs/native/` explains the threat model and pool composition.

## Risks / open questions

- BoringSSL's ClientHello composition is opinionated; per-profile overrides may require digging into `SSL_set_quic_use_legacy_codepoint`- style hooks for less-common extensions.
- Pool maintenance: browser fingerprints rotate with major releases. Pair reviews with the upstream-spec-watch cadence in `docs/native/upstream-spec-watch-runbook.md`.

## Links

- [[Epic - Control-plane hardening]]
