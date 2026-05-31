---
title: Epic - Orchestration test posture
type: epic
status: doing
area: testing
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-31
---

## Goal

Close the three untested-class gaps the audit surfaced, and build one shared deterministic failure-injection harness that every orchestration- level scenario test reuses. Parser/config fuzzing is already good — this epic is specifically about orchestration and lifecycle, where the bugs hide today.

## Why now

Cache corruption, supervisor lifecycle edges, rollback attempts, and protect-socket stalls all happen rarely in production and are impossible to reproduce without deterministic injection. Every audit-stream fix benefits from having a test that would have caught the original bug, and shares more infrastructure than it would build alone.

## Key decisions

- **One shared harness** (fake clock, scripted network, corrupt-file fixture, scripted exit causes, stall injection for the protect socket) — not bespoke fixtures per scenario.
- **Scenario tests block the fixes they regress-protect** via `blockedBy`, so the harness lands first and the scenarios follow as each matching fix merges.
- **Unit coverage for the three untested classes** (`DefaultStrategyPackService`, `AppStartupInitializer`, `VpnProtectSocketServer`) is a separate task from the scenario harness — different failure mode, different fixture needs.

## Scope

- **In scope:** shared failure-injection harness; unit tests for the three untested classes; scenario tests for cache corruption, repeated startup/shutdown, control-plane rollback, protect-socket stall.
- **Out of scope:** parser/config fuzz coverage (already good); UI tests; end-to-end device tests.

## Ship definition

- [ ] Harness documented in the test-module README with a minimal example.
- [ ] Four scenario tests use the harness and pass deterministically (no sleep-based waiting).
- [ ] Each of the three previously-untested classes has a dedicated test file covering the failure modes the audit called out.
- [ ] CI green on main after every fix-and-test pair merges.

## Child tasks

**Harness**
- Add orchestration failure-injection harness

**Unit coverage for untested classes**
- Add unit tests for orchestration gaps

**Scenario tests** (each `blockedBy` the harness)
- Add cache-corruption regression test
- Add repeated startup-shutdown supervisor test (closed task)
- Add control-plane rollback attempt test
- Add protect-socket server stall test

Child tasks roll up via the TaskNotes relationships view on this note.

## Dependencies

- Depends on: [[Epic - Control-plane hardening]] (rollback / atomic writes must exist before their regression tests).
- Depends on: [[Epic - Runtime lifecycle and supervisors]] (explicit exit causes must exist before the supervisor lifecycle test).
- Depends on: Epic - Privacy and diagnostics (reworked protect socket must exist before the stall test's assertions make sense).
- Shares test coverage with: [[Epic - Direct-mode diagnostic state machine]] (integration tests per result class, also `blockedBy` this harness).

## Risks / open questions

- Fake-clock discipline: any test that uses real time introduces flakiness. Linter rule to reject real-clock calls inside harness- governed tests?

## Links

- [[ripdpi-android]]
- Child issues: 2
