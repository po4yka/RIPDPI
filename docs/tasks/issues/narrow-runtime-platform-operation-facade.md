---
title: Narrow runtime platform operation facade
type: task
status: done
area: engine
priority: medium
owner: unassigned
parent: epic-finish-srp-residual-architecture-debt
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [x] #task Narrow runtime platform operation facade #repo/RIPDPI #area/engine #status/done 🔼

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

- [x] Runtime-platform root exposes only stable top-level contracts.
- [x] Operation families are available through focused modules or feature-gated
    namespaces.
- [x] Consumers import only the operation family they need.
- [x] Rust boundary checks prevent broad root re-export regressions.

## Links

- [[Epic - Finish SRP residual architecture debt]]
