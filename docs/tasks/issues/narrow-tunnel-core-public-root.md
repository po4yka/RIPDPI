---
title: Narrow tunnel core public root
type: task
status: backlog
area: vpn
priority: medium
owner: unassigned
parent: epic-clear-post-srp-architecture-regressions
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Narrow tunnel core public root #repo/RIPDPI #area/vpn #status/backlog 🔼

## Summary

`ripdpi-tunnel-core` root still exposes classify, device, I/O loop, sessions,
stats, tunnel API, and root-level smoke tests. The implementation modules are
split, but the public root remains a broad tunnel facade rather than a small
entrypoint plus explicit submodule namespaces.

## Audit citation

- `native/rust/crates/ripdpi-tunnel-core/src/lib.rs` lines 5-19.
- Architecture-health indicator: `broad-root-facade`, `rootExports=13`, limit
  `10`.

## Scope

- In scope: root exports, public module visibility, root-level tests, and
  consumer import migration.
- Out of scope: changing TUN runtime behavior, session semantics, or DNS cache
  behavior.

## Acceptance criteria

- [ ] Tunnel-core root exports only stable top-level entrypoints.
- [ ] Lower-level device, session, stats, and I/O-loop contracts move behind
    explicit modules or narrower facade types.
- [ ] Root-level tests move to focused test modules where possible.
- [ ] The broad-root indicator for tunnel-core is removed.

## Links

- [[Epic - Clear post-SRP architecture regressions]]
