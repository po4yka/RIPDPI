---
title: Add post-quantum hybrid KEM (X25519MLKEM768) for outbound TLS handshakes
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-16
updated: 2026-05-16
---

- [ ] #task Add post-quantum hybrid KEM (X25519MLKEM768) for outbound TLS handshakes #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-post-quantum-hybrid-kem-x25519mlkem768-for-tls-handshakes`
- **Verify:** `cargo test -p ripdpi-tls-profiles -p ripdpi-xhttp -p ripdpi-masque`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-tls-profiles/**`, `native/rust/crates/ripdpi-xhttp/**`, `native/rust/crates/ripdpi-masque/**`, `docs/native/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Negotiate the hybrid X25519MLKEM768 key exchange in TLS 1.3 ClientHello for outbound handshakes. Chrome 124+, Firefox 132+, and Cloudflare's edge already speak this; adding it makes RIPDPI's fingerprint match a modern browser's and provides forward secrecy against record-and-decrypt-later attacks by quantum adversaries.

## Context

DPI fingerprinting increasingly looks at which key-share groups a client advertises. As browsers ship X25519MLKEM768 by default, clients that *only* advertise X25519 become an anomaly that DPI can exploit as a high-precision signal. The hybrid KEM also raises the floor for nation-state record-and-decrypt threats.

BoringSSL ships X25519MLKEM768 (`SSL_GROUP_X25519_MLKEM768`); the `boring` crate may or may not expose it directly. If not, add a local extern declaration mirroring the `pin-boringssl-symbols-with- build-time-existence-check` discipline.

## Acceptance criteria

- [ ] `ripdpi-tls-profiles` exposes a `kem_groups` config option that accepts `["X25519MLKEM768", "X25519", "P256"]`-style ordered lists.
- [ ] Default for modern profiles (Chrome 130+) advertises X25519MLKEM768 first.
- [ ] All TLS-using outbounds (VLESS+Reality, xHTTP, MASQUE) honour the option.
- [ ] Negotiation-fallback test: if server doesn't speak the hybrid, falls through to X25519 without surfacing an error.
- [ ] Telemetry counter `tls.pq_kem_negotiated` increments when the hybrid is selected.

## Risks / open questions

- Hybrid KEM adds ~1 KB to the ClientHello, splitting it across two TLS records. Some old middleboxes drop split ClientHellos. Document and consider a fallback toggle for known-bad networks.
- ML-KEM is a NIST FIPS 203 standard; vendor revisions of BoringSSL may change the group ID. Pair with the upstream-watch workflow.

## Links

- [[add-utls-per-connection-tls-fingerprint-rotation]]
- [[add-ech-encrypted-client-hello-for-tls-outbounds]]
- IETF draft-ietf-tls-hybrid-design
