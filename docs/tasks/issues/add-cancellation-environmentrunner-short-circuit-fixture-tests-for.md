---
title: Add cancellation + EnvironmentRunner short-circuit fixture tests for connectivity runners
type: task
status: todo
area: testing
priority: high
owner: Test Automation Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task Add cancellation + EnvironmentRunner short-circuit fixture tests for connectivity runners #repo/RIPDPI #area/testing #status/todo ⏫

## Objective
Add two fixture tests in `ripdpi-monitor-engine` covering gates G3 (cancellation short-circuit) and G4 (`EnvironmentRunner::run` four-case behaviour) of POY-12.

## Context
The `support::collect_family_steps` helper now centralises the per-target cancellation check (`cancel.load(Ordering::Acquire)`); a regression that flips the polling order or drops the early-return `None` would silently leak partial probes into the final report. `EnvironmentRunner::run` retains a finalisation short-circuit when `transport == "none" && !vpn_service_was_active`, plus an unvalidated-network warn event; both are part of the user-visible contract.

## Acceptance criteria

G3 — `support::collect_family_steps` cancellation:
- New unit test using a stub `ConnectivityProbeFamily` (e.g., bound to `DnsTarget` or a synthetic target type) and a pre-set `AtomicBool` cancel flag.
- Assert that with cancel pre-set, `collect_family_steps` returns `None` immediately and zero `run_probe` calls are made.
- Assert that with cancel set after the first probe, `collect_family_steps` returns `None` after exactly one `run_probe` call (partial-stop semantics).

G4 — `EnvironmentRunner::run` four cases:
- Case (a) `network_snapshot is None` → `RunnerOutcome::Completed`, no warn event, no record_step.
- Case (b) `transport == "none" && !vpn_service_was_active` → warn event "OS reports no network; aborting scan", `runtime.finish_with_report(...)` called, returns `RunnerOutcome::Finished`.
- Case (c) `transport == "none" && vpn_service_was_active` → `Completed`, no abort, no warn for no-network branch.
- Case (d) `!validated && !captive_portal` → warn event "OS reports unvalidated network; probe results may be unreliable", returns `Completed`.
- Use the existing `ExecutionRuntime` test scaffold; assert against `shared.lock().events`.

## Required verification
- `cargo nextest run -p ripdpi-monitor-engine -E 'test(cancel) or test(environment)'` green.
- Each case is an independent `#[test]` so a regression points at the specific failing branch.

## Risks
None — test-only.

## Definition of done
- Both tests added, green locally and in CI.
- POY-12 gates G3 and G4 marked satisfied.
