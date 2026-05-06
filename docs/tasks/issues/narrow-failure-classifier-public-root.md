---
title: Narrow failure classifier public root
type: task
status: done
area: engine
priority: medium
owner: unassigned
parent: epic-clear-post-srp-architecture-regressions
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [x] #task Narrow failure classifier public root #repo/RIPDPI #area/engine #status/done 🔼

## Summary

`ripdpi-failure-classifier` has split internal families, but the crate root
still publicly re-exports block detection, connection freeze, DNS, HTTP, QUIC,
strategy execution, TLS, transport, and shared failure types. This leaves one
root as the default broad classifier surface for runtime and diagnostics
callers.

## Audit citation

- `native/rust/crates/ripdpi-failure-classifier/src/lib.rs` lines 14-26.
- Architecture-health indicator: `broad-root-facade`, `rootExports=11`, limit
  `10`.

## Scope

- In scope: root exports, feature-family modules, consumer import migration, and
  compatibility facade decisions.
- Out of scope: changing classifier semantics or failure DTO field meanings.

## Acceptance criteria

- [x] Stable failure DTOs remain easy to import without pulling every classifier
    family through the root.
- [x] Classifier-family functions are exposed through focused modules or narrow
    facade traits.
- [x] Runtime and diagnostics callers import only the classifier family they use.
- [x] The broad-root indicator for this file is removed or explicitly justified
    by a narrower rule.

## Links

- [[Epic - Clear post-SRP architecture regressions]]
