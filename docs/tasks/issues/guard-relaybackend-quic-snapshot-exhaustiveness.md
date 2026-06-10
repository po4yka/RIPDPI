---
title: "Guard RelayBackend manual match arms against silently-omitted QUIC variants"
type: task
status: backlog
area: relay
priority: low
owner: unassigned
parent: epic-june-2026-audit-remediation
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Motivation

The 2026-06-10 Rust API audit noted `RelayBackend` reached 14 variants (was 12; Mieru and Ssh added). The `dispatch_pooled_backend!` macro was updated correctly, but three manual `match self` blocks — `quic_migration_snapshot()`, `chain_hop_snapshot()`, `open_udp_session()` — remain a maintenance hazard. A future QUIC-capable variant added without updating `quic_migration_snapshot()` would silently return `(None, None)` with **no compile error**, producing a quiet correctness bug rather than a build failure.

## Proposed change

1. Make the manual matches exhaustive-by-construction: remove any catch-all `_ =>` arm in `quic_migration_snapshot` / `chain_hop_snapshot` / `open_udp_session` so adding a variant forces a compile error (`#[deny(unreachable_patterns)]` / non-`_` exhaustive match), OR
2. Add a compile-time assertion / test that enumerates all variants and asserts each is handled by the QUIC-snapshot path it belongs to.

## Acceptance criteria

- [ ] PR confirms current 14-variant shape and the three manual-match sites.
- [ ] Adding a new `RelayBackend` variant now fails to compile until the QUIC/chain/UDP snapshot matches are updated (no silent `(None, None)`).
- [ ] `cargo nextest run -p ripdpi-relay-core --locked` green; clippy clean.

## Risks / open questions

- Some variants legitimately have no QUIC migration — the goal is to force an *explicit* decision per variant, not to require all variants be QUIC-capable.

## References

- Audit memory `project_native_audit_findings.md` (2026-06-10, item / RelayBackend enum).
- `desync-engine` / relay-core enum delegation (`dispatch_pooled_backend!`).
