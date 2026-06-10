---
title: "Epic - TLS/QUIC handshake hardening"
type: epic
status: doing
area: epic
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Goal

Harden the observable handshake of RIPDPI's owned TLS/QUIC stack against fingerprinting and active probing: per-connection ClientHello fingerprint rotation, post-quantum hybrid key exchange, pre-handshake SNI desync, and resilient H3→H2 fallback with telemetry. These tasks all converge on the same surface (`ripdpi-tls-profiles` `ProfileConfig` and the transports that consume it), so they are coordinated to avoid conflicting edits.

## Why now

Two of these tasks (`add-utls-per-connection-tls-fingerprint-rotation`, `add-post-quantum-hybrid-kem`) both edit `ProfileConfig` and wire the same call sites in `ripdpi-relay-tls-transports`/`ripdpi-shadowtls`/`ripdpi-vless`/`ripdpi-xhttp`; landing them independently risks merge churn on the shared config type. Grouping them sets a single serialized lane for `ProfileConfig` changes and a shared atomic-counter telemetry pattern.

## Key decisions

- **`ProfileConfig` is the shared contract.** uTLS rotation adds a `Profile::Rotating` option; PQ KEM adds `kem_groups: Option<Vec<String>>`. Both flow through `apply.rs`. Land them in one lane (or one PR) to avoid conflicting edits, per the high-risk-file ledger in CLAUDE.md.
- **Telemetry mirrors the existing atomic-counter pattern** (`FINGERPRINT_ROTATION_ACTIVE`-style `AtomicU64`), pulled at 1 Hz — never per-packet (`rust-android-telemetry` discipline).
- **No real ECH** (per ADR 0001); SNI handling here is desync/spoof and rotation, not ECH.

## Scope

- **In scope:** per-connection uTLS fingerprint rotation, X25519MLKEM768 hybrid KEM with fallback, pre-handshake ClientHello SNI desync, and H3→H2 MASQUE fallback telemetry sufficiency validation.
- **Out of scope:** the owned-TLS JA3/JA4 CI snapshot pin (separate infra task), and transport additions (extended-outbound epic).

## Child tasks

- [[add-utls-per-connection-tls-fingerprint-rotation]] — add `Profile::Rotating` + wire the four consuming transports.
- [[add-post-quantum-hybrid-kem-x25519mlkem768-for-tls-handshakes]] — add `kem_groups` to `ProfileConfig`, fallback test, AtomicU64 counter.
- [[adopt-tls-spoof-prehandshake-clienthello-sni-desync]] — pre-handshake SNI desync for whitelist bypass.
- [[add-h3-to-h2-fallback-telemetry-rollout-validation]] — snapshot-capture tests per `H3FallbackReason` variant (easiest close).

## Ship definition

- [ ] ClientHello fingerprint rotates per connection across all four consuming transports, with a pull-model active-state counter.
- [ ] X25519MLKEM768 hybrid KEM negotiates with a tested classical fallback path.
- [ ] Pre-handshake SNI desync is available as a strategy option with a packet-smoke scenario.
- [ ] H3→H2 fallback telemetry captures every `H3FallbackReason` variant in a snapshot test.

## Risks / open questions

- `ProfileConfig` is a moderate-fan-in type; coordinate edits in one lane.
- PQ KEM adds handshake size; verify it does not trip the RU home-ISP ClientHello-size sensitivities tracked elsewhere.

## References

- ADR 0001 (Reality/ECH), `rust-android-telemetry` skill, `desync-engine` skill.
- Token/transport surfaces: `ripdpi-tls-profiles`, `ripdpi-relay-tls-transports`, `ripdpi-vless`, `ripdpi-xhttp`, `ripdpi-shadowtls`.
