---
title: Narrow diagnostics runner public surface
type: task
status: done
area: diagnostics
priority: medium
owner: unassigned
parent: epic-finish-srp-residual-architecture-debt
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [x] #task Narrow diagnostics runner public surface #repo/RIPDPI #area/diagnostics #status/done 🔼

## Summary

The old diagnostics-probes crate is gone, but the runner root now publicly
re-exports HTTP, DNS, TLS, Telegram, transport, candidates, classification,
fat-header, observations, and utility surfaces. Keep the execution crate from
becoming a broad diagnostics facade again.

## Audit citation

- `native/rust/crates/ripdpi-diagnostics-runner/src/lib.rs` lines 5-60.

## Scope

- In scope: diagnostics runner public exports, downstream imports, and
  migration paths to protocol-specific crates.
- Out of scope: reintroducing `ripdpi-diagnostics-probes` as a facade.

## Acceptance criteria

- [x] Runner root exposes only execution-owned types and entry points.
- [x] Consumers import protocol helpers from split protocol crates directly.
- [x] Compile tests or boundary checks prevent broad re-export regressions.
- [x] Diagnostics runner tests stay green.

## Links

- [[Epic - Finish SRP residual architecture debt]]
