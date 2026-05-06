---
title: Narrow privileged ops public root
type: task
status: backlog
area: engine
priority: medium
owner: unassigned
parent: epic-clear-post-srp-architecture-regressions
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Narrow privileged ops public root #repo/RIPDPI #area/engine #status/backlog 🔼

## Summary

`ripdpi-privileged-ops` has operation-family modules, but its root still
re-exports capability probing, experimental tier-3 packet operations, socket
protection, fragmentation, raw packet sends, socket options, TCP info, TCP
repair, TTL helpers, and shared operation types. This keeps privileged syscall
families reviewable through one broad public facade.

## Audit citation

- `native/rust/crates/ripdpi-privileged-ops/src/lib.rs` lines 15-36.
- Architecture-health indicator: `broad-root-facade`, `rootExports=11`, limit
  `10`.

## Scope

- In scope: public root exports, operation-family namespaces, runtime-platform
  import migration, and architecture contract updates.
- Out of scope: changing syscall behavior, root-helper protocol semantics, or
  unsafe boundaries except where imports move.

## Acceptance criteria

- [ ] Privileged operation families are imported through focused modules.
- [ ] The crate root re-exports only stable shared contracts intentionally meant
    for broad consumers.
- [ ] Platform callers no longer depend on a one-stop privileged operation root.
- [ ] Native architecture contracts and hotspot checks pass.

## Links

- [[Epic - Clear post-SRP architecture regressions]]
