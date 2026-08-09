---
id: RLY-1786264762917178
title: Guard RelayBackend manual match arms against silently-omitted QUIC variants
kind: bug
status: todo
area: relay
priority: medium
risk: high
owner: Relay maintainer
parent: EPC-1786264762917503
blocked_by: []
spec_mode: required
openspec_change: rly-1786264762917178-guard-relaybackend-quic-snapshot-exhaustiveness
created: 2026-06-10
updated: 2026-08-09
source_wiki_pages: []
---

## Motivation

The 2026-06-10 Rust API audit noted `RelayBackend` reached 14 variants (was 12; Mieru and Ssh added). The `dispatch_pooled_backend!` macro was updated correctly. Re-verified 2026-06-11 against `native/rust/crates/ripdpi-relay-core/src/backend.rs`: of the three manual `match self` blocks, `quic_migration_snapshot()` (`backend.rs:85-102`) and `open_udp_session()` (`backend.rs:122-141`) already enumerate all 14 variants with explicit `|`-joined arms and **no** catch-all `_`, so adding a variant fails to compile (non-exhaustive match) — they are already guarded. Only `chain_hop_snapshot()` (`backend.rs:104-108`) has a catch-all `_ => None` arm: a future chain-capable variant added without updating it would silently return `None` with **no compile error**, producing a quiet correctness bug rather than a build failure. That is the remaining hazard.

## Proposed change

1. Remove the catch-all `_ => None` arm in `chain_hop_snapshot` (the only remaining non-exhaustive site) and enumerate every variant explicitly, matching the already-exhaustive `quic_migration_snapshot` / `open_udp_session` pattern, so adding a variant forces a compile error, OR
2. Add a test that enumerates all variants and asserts each is handled by the snapshot path it belongs to (covering all three sites, including the two already exhaustive).

## Acceptance criteria

- [ ] PR confirms current 14-variant shape and the three manual-match sites.
- [ ] Adding a new `RelayBackend` variant now fails to compile until the QUIC/chain/UDP snapshot matches are updated (no silent `(None, None)`).
- [ ] `cargo nextest run -p ripdpi-relay-core --locked` green; clippy clean.

## Risks / open questions

- Some variants legitimately have no QUIC migration — the goal is to force an *explicit* decision per variant, not to require all variants be QUIC-capable.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item / RelayBackend enum).
- `desync-engine` / relay-core enum delegation (`dispatch_pooled_backend!`).
