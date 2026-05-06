---
title: Isolate proxy runtime policy decisions behind ports
type: task
status: done
area: engine
priority: high
owner: unassigned
parent: epic-finish-srp-residual-architecture-debt
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [x] #task Isolate proxy runtime policy decisions behind ports #repo/RIPDPI #area/engine #status/done ⏫

## Summary

Proxy runtime still has direct policy-engine edges. The dependency hub is
smaller, but proxy-runtime still directly depends on `ripdpi-runtime-adaptive`
and `ripdpi-runtime-policy`, linking socket execution to policy-selection
crates instead of depending only on selected route/action ports.

## Audit citation

- `native/rust/crates/ripdpi-proxy-runtime/Cargo.toml` lines 10-25.

## Scope

- In scope: proxy runtime dependencies, runtime source imports, selected
  decision port boundaries, and tests that protect dependency direction.
- Out of scope: changing policy semantics or desync candidate behavior.

## Acceptance criteria

- [x] Proxy runtime no longer depends directly on `ripdpi-runtime-adaptive` or
    `ripdpi-runtime-policy`.
- [x] Handshake, UDP, routing, relay, and warmup code consume narrow runtime
    ports or selected decisions.
- [x] Boundary tests or CI checks fail when proxy runtime reintroduces direct
    policy-engine imports.
- [x] Existing Rust workspace tests and `rust-clippy` stay green.

## Links

- [[Epic - Finish SRP residual architecture debt]]
