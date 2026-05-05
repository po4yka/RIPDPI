---
title: Evaluate and remove ripdpi-diagnostics-probes zero-logic facade crate
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

- [ ] #task Evaluate and remove ripdpi-diagnostics-probes zero-logic facade crate #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Remove `ripdpi-diagnostics-probes` if it contains no implementation logic, migrating its consumers to depend on `ripdpi-diagnostics-runner` directly.

## Context

`ripdpi-diagnostics-probes` depends on all 10 diagnostics sub-crates including `ripdpi-diagnostics-runner`. It appears to be a re-export facade with no implementation. `ripdpi-diagnostics-runner` already aggregates the same probe crates and provides the orchestration API. The facade crate creates a diamond aggregation where consumers pull `ripdpi-diagnostics-runner` twice and adds fan-out 10 with no value.

Source: `native/rust/crates/ripdpi-diagnostics-probes/Cargo.toml`

## Acceptance criteria

- [ ] Audit `ripdpi-diagnostics-probes/src/lib.rs` — confirm it adds no implementation logic beyond re-exports.
- [ ] Identify all consumers of `ripdpi-diagnostics-probes` via `cargo tree --workspace -i ripdpi-diagnostics-probes`.
- [ ] Migrate each consumer to depend on `ripdpi-diagnostics-runner` (or the specific sub-crate it needs) directly.
- [ ] Remove `ripdpi-diagnostics-probes` from workspace members and delete the directory.
- [ ] `cargo build --workspace` green.

## Definition of done

`ripdpi-diagnostics-probes` absent from workspace; all former consumers build against `ripdpi-diagnostics-runner` or specific sub-crates directly.
