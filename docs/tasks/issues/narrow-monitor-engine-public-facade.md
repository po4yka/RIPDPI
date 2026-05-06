---
title: Narrow monitor engine public facade
type: task
status: done
area: diagnostics
priority: medium
owner: unassigned
parent: epic-clear-post-srp-architecture-regressions
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [x] #task Narrow monitor engine public facade #repo/RIPDPI #area/diagnostics #status/done 🔼

## Summary

`ripdpi-monitor-engine/src/lib.rs` is smaller than before but still re-exports
execution/runtime types plus a large diagnostics-contract DTO set and
`TransportConfig` from one root facade. The native hotspot budget now fails this
file by one production line, which signals that the root still carries too much
contract plumbing.

## Audit citation

- `native/rust/crates/ripdpi-monitor-engine/src/lib.rs` lines 28-46.
- Native hotspot budget: measured `45` production LOC, budget `44`.

## Scope

- In scope: monitor-engine public root exports, explicit facade modules,
  consumer import migration, and hotspot budget compliance.
- Out of scope: changing diagnostics schema fields or scan execution behavior.

## Acceptance criteria

- [x] `lib.rs` exposes only the intended stable monitor-engine entrypoints.
- [x] Broad diagnostics DTO re-exports move to explicit namespaces or direct
    consumer imports.
- [x] `native/rust/crates/ripdpi-monitor-engine/src/lib.rs` is under the native
    hotspot budget.
- [x] `python3 scripts/ci/check_native_hotspot_budgets.py` passes.

## Links

- [[Epic - Clear post-SRP architecture regressions]]
