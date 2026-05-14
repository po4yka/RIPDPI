---
title: Adopt HandleReservation primitive in NetworkDiagnostics (cancelScan latency)
type: task
status: blocked
area: service
priority: high
owner: Principal Android Rust Architect
parent: epic-runtime-lifecycle-and-supervisors
blocks: []
blocked_by: [decouple-jni-handle-lifetime-and-telemetry-locking]
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task Adopt HandleReservation primitive in NetworkDiagnostics (cancelScan latency) #repo/RIPDPI #area/service #status/blocked ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `adopt-handlereservation-primitive-in-networkdiagnostics-cancelscan-latency`
- **Verify:** `just test-module core:engine`
- **Scope (only modify these + this file + the ledger):** `core/engine/src/**`
- **Blocked-by (must be DONE in the ledger first):** `decouple-jni-handle-lifetime-and-telemetry-locking`
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Follow-up to POY-175. Replace the single `kotlinx.coroutines.sync.Mutex` in `NetworkDiagnostics` with the `HandleReservation` primitive landed by POY-175. This is the highest-severity sibling because `cancelScan()` is the operator-visible "abort scan" path: today a long-running `pollProgressJson()` JNI call serializes `cancelScan()`, directly affecting perceived responsiveness.

## Audit citation

- `core/engine/src/main/kotlin/com/poyka/ripdpi/core/NetworkDiagnostics.kt:99-165`
- lines 136-152: `pollProgressJson` / `takeReportJson` / `pollPassiveEventsJson` hold `mutex` across the JNI call.
- line 128: `cancelScan()` waits on the same mutex.
- line 154: `destroy()` waits on the same mutex.

ADR: `docs/architecture/jni-handle-lifetime-telemetry-lock.md` ("Sibling Wrapper Audit").

## Acceptance criteria

- [ ] `NetworkDiagnostics` uses `HandleReservation`; `mutex` field removed.
- [ ] `cancelScan()` is not delayed by an in-flight `pollProgressJson` / `takeReportJson` / `pollPassiveEventsJson` past the in-flight JNI call's own duration.
- [ ] `destroy()` waits only for in-flight reservations to drain.
- [ ] `ensureHandleLocked()` semantics preserved: lazy native session creation runs under the lifetime region, not under a reservation.
- [ ] `NetworkDiagnosticsLockingTest` covers `cancelScanNotBlockedByPollProgress`, `destroyWaitsForInFlightPolls`, `lazyHandleCreatedExactlyOnce`.
- [ ] No JNI ABI change.

## Blocker

Blocked on POY-175 (the `HandleReservation` primitive must land first).

## Stop gate

No diagnostics catalog change, no protobuf/contract change, no live-network behavior change.

## Links

- [[Epic - Runtime lifecycle and supervisors]] (POY-56)
- POY-175 (primitive provider)
- ADR `docs/architecture/jni-handle-lifetime-telemetry-lock.md`
