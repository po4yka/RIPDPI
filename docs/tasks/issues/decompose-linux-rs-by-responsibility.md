---
title: Decompose linux.rs by responsibility
type: task
status: backlog
area: service
priority: medium
owner: unassigned
parent: epic-native-hotspot-decomposition
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Decompose linux.rs by responsibility #repo/RIPDPI #area/service #status/backlog 🔼

## Summary

`linux.rs` (1557 LOC) mixes socket options, protect logic, raw sends, TCP
repair, TTL capture, and low-level packet mutation. Split by responsibility.

## Audit citation

- `native/rust/crates/ripdpi-runtime/src/platform/linux.rs` — 1557 LOC.

## Acceptance criteria

- [ ] Split into: `sockopts`, `protect`, `raw_send`, `tcp_repair`.
- [ ] Each module has scoped unit tests where feasible.
- [ ] No behavior change — existing tests green.
- [ ] `file-loc-baseline.json` updated.

## Links

- [[Epic - Native hotspot decomposition]]
- [[ripdpi-android-audit-2026-04-20]]
