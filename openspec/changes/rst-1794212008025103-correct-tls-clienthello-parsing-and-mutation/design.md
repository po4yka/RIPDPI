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

## CI repair

- The fuzz workspace owns a separate Cargo lockfile. Reconcile its existing `ripdpi-xhttp` dependency graph without changing package versions.
- The ECH adapter owns a non-owning request ID type. The JNI bridge converts its unchanged signed Long representation at the boundary. Invalid, stale, cancelled, and released IDs retain their previous behavior.
- Keep the unsafe-boundary scanner and all enforcement enabled. Validate the scanner, locked fuzz smoke, adapter behavior, JNI contract, and combined source gates.

## Tor SQLite security gate

- The Tor directory manager uses bundled SQLite. Upgrade to a version that fixes RUSTSEC-2026-0459 without weakening the advisory gate. If the published manager constrains the fixed rusqlite version, carry a narrow provenance-recorded dependency patch and test its compatibility.
- Keep the Tor cache contract and Android targets. Vet every added package before compilation and reconcile both Cargo lockfiles.

## Diagnostics CI blocker

- Resolve the existing diagnostics Detekt findings without suppressions or baseline changes. Preserve ECH cancellation and request release behavior, including cancellation racing with a connection failure, and retain HTTPS RR parsing and alias limits.
