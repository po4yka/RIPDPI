---
title: Move diagnostics-net facade behind explicit compatibility namespace
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: epic-finish-srp-residual-architecture-debt
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Move diagnostics-net facade behind explicit compatibility namespace #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Summary

`ripdpi-diagnostics-net` root re-exports DNS, HTTP, TLS, Telegram, transport,
fat-header, wire, and shared utility surfaces. If compatibility requires a
facade, make it explicit and opt-in so new consumers do not bypass the split
protocol crates.

## Audit citation

- `native/rust/crates/ripdpi-diagnostics-net/src/lib.rs` lines 1-60.

## Scope

- In scope: diagnostics-net root exports, compatibility namespace, deprecation
  guidance, and downstream import migration.
- Out of scope: reintroducing a broad diagnostics-probes crate.

## Acceptance criteria

- [ ] Broad re-exports are removed from the root or moved behind an explicit
    compatibility namespace.
- [ ] New consumers have clear imports from protocol-specific crates.
- [ ] Existing compatibility consumers are migrated or intentionally annotated.
- [ ] Boundary tests prevent accidental facade expansion.

## Links

- [[Epic - Finish SRP residual architecture debt]]
