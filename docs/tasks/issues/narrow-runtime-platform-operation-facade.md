---
title: Narrow runtime platform operation facade
type: task
status: backlog
area: engine
priority: medium
owner: unassigned
parent: epic-finish-srp-residual-architecture-debt
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Narrow runtime platform operation facade #repo/RIPDPI #area/engine #status/backlog 🔼

## Summary

`ripdpi-runtime-platform` root publicly exposes capability probing,
experimental tier-3 operations, fake sends, IP fragmentation,
original-destination lookup, socket options, `TCP_INFO`, TTL reads, protect
hooks, and root-helper clients. Narrow the root surface so unrelated platform
operation families are not exposed through one facade.

## Audit citation

- `native/rust/crates/ripdpi-runtime-platform/src/lib.rs` lines 25-60.

## Scope

- In scope: runtime-platform public exports, operation-family modules, import
  migration, and boundary checks.
- Out of scope: changing low-level platform behavior or privileged operation
  semantics.

## Acceptance criteria

- [ ] Runtime-platform root exposes only stable top-level contracts.
- [ ] Operation families are available through focused modules or feature-gated
    namespaces.
- [ ] Consumers import only the operation family they need.
- [ ] Rust boundary checks prevent broad root re-export regressions.

## Links

- [[Epic - Finish SRP residual architecture debt]]
