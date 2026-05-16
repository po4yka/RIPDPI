---
title: Add uTLS per-connection TLS-fingerprint rotation for outbound TLS handshakes
type: task
status: backlog
area: rust-native
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-16
updated: 2026-05-16
---

- [ ] #task Add uTLS per-connection TLS-fingerprint rotation for outbound TLS handshakes #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-utls-per-connection-tls-fingerprint-rotation`
- **Verify:** `cargo test -p ripdpi-tls-profiles -p ripdpi-vless -p ripdpi-xhttp`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-tls-profiles/**`, `native/rust/crates/ripdpi-vless/**`, `native/rust/crates/ripdpi-xhttp/**`, `native/rust/crates/ripdpi-shadowtls/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Implement uTLS-style ClientHello fingerprint impersonation rotated
on each outbound TLS connection. Current `ripdpi-tls-profiles`
emits a single profile per session; DPI that fingerprints exact
ClientHello bytes (extension order, GREASE values, cipher suite
list) can correlate sessions even when domains and IPs vary.

## Context

Russian TSPU and similar nation-state DPI systems increasingly use
ClientHello fingerprinting (JA3/JA4 hashes) as a high-confidence
proxy-detection signal. Rotating the impersonated fingerprint per
connection breaks this signal entirely.

Reference implementations: refraction-networking/utls (Go),
sing-box's uTLS integration.

## Acceptance criteria

- [ ] `ripdpi-tls-profiles` exposes a `RotatingProfileSelector`
    that picks from a pool of browser fingerprints per connection.
- [ ] Pool covers at least: Chrome 130, Firefox 125, Safari 18,
    iOS 18 Safari, Edge 130.
- [ ] Each TLS outbound (VLESS, xHTTP, ShadowTLS) accepts a
    `Profile::Rotating` config option that consults the selector.
- [ ] Unit tests assert: every connection consumes a fresh profile;
    profile distribution is roughly uniform over 1000 trials.
- [ ] Telemetry counter `tls.fingerprint_rotation_active` increments
    on each rotated handshake.
- [ ] Documentation under `docs/native/` explains the threat model
    and pool composition.

## Risks / open questions

- BoringSSL's ClientHello composition is opinionated; per-profile
  overrides may require digging into `SSL_set_quic_use_legacy_codepoint`-
  style hooks for less-common extensions.
- Pool maintenance: browser fingerprints rotate with major releases.
  Pair with `recurring-upstream-watch-for-xray-core-reality-ech-xhttp-changes`
  cadence.

## Links

- [[Epic - Control-plane hardening]]
- [[add-spec-version-pinning-and-upstream-watch-ci-for-vendored-protocols]]
