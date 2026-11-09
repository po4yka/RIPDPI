## Context

The audit identified four defects in packet inspection and fake ClientHello mutation. Live traffic and intentionally incomplete fake templates have different needs.

## Goals / Non-Goals

- Goal: Fix the four findings and keep input bytes on failure.
- Non-goal: Implement a TLS endpoint, ECH decryption, or change network policy.

## Decisions

- Validate nested SNI bounds without removing the established fake-template behavior.
- Reassemble ClientHello record payload for host inspection and map spans to original bytes; do not misrepresent a discontiguous host as a contiguous slice.
- Preserve trailing bytes or reject the mutation unchanged. Keep TLS 1.3 PSK ordering.

## Contracts and ownership

- `ripdpi-packets`: parser and SNI mutation; `ripdpi-proxy-runtime-desync-adapter` and classifier: consumer integration. No Kotlin, JNI, proto, storage, or config schema changes.
- Writers use separate worktrees. SNI bounds owns `tls_nom.rs`; mutation owns `tls/mutation/sni.rs`; record handling owns its consumer paths. Integrator serializes shared tests and API snapshots.

## Risks / Trade-offs

- Rejecting malformed input can change fake-template behavior; run profile and golden tests. Record-aware parsing needs explicit mapping for split names.

## Migration Plan

No data migration. Revert the relevant commits to roll back. Run targeted locked Cargo tests, independent review, and `just ci-gates` before integration.
