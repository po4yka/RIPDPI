---
title: Replace glob re-exports of ripdpi-diagnostics-contracts in 9 diagnostics crates
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Replace glob re-exports of ripdpi-diagnostics-contracts in 9 diagnostics crates #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Replace `pub use ripdpi_diagnostics_contracts::*` in each of the 9 diagnostics crates with selective imports, exposing only the subset each crate actually wraps.

## Context

Nine crates each do `pub use ripdpi_diagnostics_contracts::*` at crate root, re-exporting all 104 public items from contracts. This means the same type is reachable under 9 different module paths. Any addition to `ripdpi-diagnostics-contracts` automatically widens the API surface of all 9 consumer crates without an explicit visibility decision. `ripdpi-diagnostics-classification` also exposes the same 104 items inside a nested `types {}` module — a double exposure.

Affected crates: `ripdpi-diagnostics-classification`, `ripdpi-diagnostics-net`, `ripdpi-diagnostics-candidates`, `ripdpi-diagnostics-probes`, `ripdpi-diagnostics-runner`, `ripdpi-diagnostics-dns`, `ripdpi-diagnostics-http`, `ripdpi-diagnostics-telegram`, `ripdpi-diagnostics-transport`.

## Acceptance criteria

- [ ] Each crate lists only the `ripdpi_diagnostics_contracts` items it actually uses in its own public API with named `pub use` statements.
- [ ] The `types {}` re-export module in `ripdpi-diagnostics-classification` is removed.
- [ ] No downstream crate breaks (all existing imports still resolve, either directly from `ripdpi-diagnostics-contracts` or from the one crate that logically owns the re-export).
- [ ] `cargo deny check` and `cargo doc` pass on the workspace.

## Definition of done

Zero `pub use ripdpi_diagnostics_contracts::*` lines remain in the workspace; `cargo build --workspace` green.
