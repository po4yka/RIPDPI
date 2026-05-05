---
title: Replace RelayRuntime Mutex fields with OnceLock and ArcSwap
type: task
status: backlog
area: relay
priority: high
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Replace RelayRuntime Mutex fields with OnceLock and ArcSwap #repo/RIPDPI #area/relay #status/backlog ⏫

## Objective

Eliminate per-session Mutex contention in `RelayRuntime` by replacing write-once fields with `OnceLock` and telemetry fields with `ArcSwap`, removing both hot-path lock serialization and Mutex poison risk.

## Context

`RelayRuntime` (runtime.rs:35–46) holds five `Mutex<Option<...>>` fields. `listener_address` and `backend` are write-once after `run()` starts — they should be `OnceLock`. `last_target`, `last_error`, `last_handshake_error` have last-write-wins telemetry semantics and are written on every session in spawned tasks. Eight `.expect()` calls on Mutex locks mean any in-session panic poisons the runtime for all subsequent connections. The accept loop locks `listener_address` twice per cycle (lines 123–125).

Source: `native/rust/crates/ripdpi-relay-core/src/runtime.rs:35-46, 69, 123-125, 140-165`

## Acceptance criteria

- [ ] `listener_address` and `backend` fields changed to `OnceLock<String>` and `OnceLock<Arc<RelayBackend>>` respectively.
- [ ] `last_target`, `last_error`, `last_handshake_error` changed to `ArcSwap<Option<Arc<str>>>` (or equivalent lock-free type).
- [ ] All eight `.expect()` calls on Mutex locks removed.
- [ ] Accept loop acquires no lock during steady-state accept iterations.
- [ ] `relay_core` benchmarks show no per-session lock contention regression.
- [ ] All relay runtime tests pass.

## Definition of done

Zero `.expect()` on lock acquisitions in production relay paths; `cargo nextest run -p ripdpi-relay-core` green.
