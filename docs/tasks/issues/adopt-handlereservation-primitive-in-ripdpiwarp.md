---
title: Adopt HandleReservation primitive in RipDpiWarp
type: task
status: blocked
area: service
priority: medium
owner: Principal Android Rust Architect
parent: epic-runtime-lifecycle-and-supervisors
blocks: []
blocked_by: [decouple-jni-handle-lifetime-and-telemetry-locking]
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task Adopt HandleReservation primitive in RipDpiWarp #repo/RIPDPI #area/service #status/blocked 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `adopt-handlereservation-primitive-in-ripdpiwarp`
- **Verify:** `just test-module core:engine`
- **Scope (only modify these + this file + the ledger):** `core/engine/src/**`
- **Blocked-by (must be DONE in the ledger first):** `decouple-jni-handle-lifetime-and-telemetry-locking`
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Follow-up to POY-175. Replace the single `kotlinx.coroutines.sync.Mutex` in `RipDpiWarp` with the `HandleReservation` primitive landed by POY-175 so `pollTelemetry()` no longer head-of-line-blocks `stop()` and vice versa.

## Audit citation

- `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiWarp.kt:200-377`
- line 360: `pollTelemetry()` holds `mutex` across the JNI call.
- line 344: `stop()` waits on the same mutex to swap the volatile `handle`.

ADR: `docs/architecture/jni-handle-lifetime-telemetry-lock.md` ("Sibling Wrapper Audit").

## Acceptance criteria

- [ ] `RipDpiWarp` uses `HandleReservation`; `mutex` field removed.
- [ ] `pollTelemetry()` and `stop()` may interleave: a long-running `pollTelemetry` JNI call does not delay `stop()` past its in-flight duration.
- [ ] Lifetime transitions (`start` create/destroy) remain serialized against active reservations.
- [ ] `RipDpiWarpLockingTest` covers `telemetryDoesNotBlockStop` and `stopWaitsForInFlightTelemetry`.
- [ ] No JNI ABI change. `ripdpi-warp` cdylib unchanged.
- [ ] No detekt/lint baseline extension.

## Blocker

Blocked on POY-175 (the `HandleReservation` primitive must land first).

## Stop gate

No protocol behavior, telemetry JSON, root-only, or live-network change. Non-rooted Android baseline preserved.

## Links

- [[Epic - Runtime lifecycle and supervisors]] (POY-56)
- POY-175 (primitive provider)
- ADR `docs/architecture/jni-handle-lifetime-telemetry-lock.md`
