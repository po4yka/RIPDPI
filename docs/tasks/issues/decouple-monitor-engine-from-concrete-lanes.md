---
title: Decouple ripdpi-monitor-engine from concrete diagnostics lanes
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

- [ ] #task Decouple ripdpi-monitor-engine from concrete diagnostics lanes #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Introduce lane-registration or runner-adapter contracts so `ripdpi-monitor-engine` links only interfaces and a separate composition crate wires in the concrete lanes.

## Context

`ripdpi-monitor-engine` directly depends on every concrete diagnostics lane: candidates, classification, DNS, HTTP, runner, Telegram, TLS, transport, failure classification, proxy config, runtime platform, and telemetry. This keeps orchestration coupled to probe implementation crates.

Source: `native/rust/crates/ripdpi-monitor-engine/Cargo.toml:10-39`

## Acceptance criteria

- [ ] Define a `DiagnosticsLane` trait (or equivalent interface) in a new `ripdpi-monitor-api` crate.
- [ ] `ripdpi-monitor-engine` depends only on `ripdpi-monitor-api`, not on any concrete lane crates.
- [ ] A new `ripdpi-monitor-composition` crate (or existing wiring crate) takes the concrete lane deps and wires them via the trait.
- [ ] All existing monitor-engine tests pass; no functional change.
- [ ] `cargo deny check` passes with the new dependency graph.

## Definition of done

`ripdpi-monitor-engine/Cargo.toml` lists no concrete lane crates; composition crate compiles and tests green.
