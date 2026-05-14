---
title: Adopt HandleReservation primitive in Tun2SocksTunnel
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

- [ ] #task Adopt HandleReservation primitive in Tun2SocksTunnel #repo/RIPDPI #area/service #status/blocked 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `adopt-handlereservation-primitive-in-tun2sockstunnel`
- **Verify:** `just test-module core:engine`
- **Scope (only modify these + this file + the ledger):** `core/engine/src/**`
- **Blocked-by (must be DONE in the ledger first):** `decouple-jni-handle-lifetime-and-telemetry-locking`
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Follow-up to POY-175. Replace the single `kotlinx.coroutines.sync.Mutex` in `Tun2SocksTunnel` with the `HandleReservation` primitive landed by POY-175 so `stats()` and `telemetry()` (both on the supervisor hot poll path) no longer head-of-line-block `stop()` and each other.

## Audit citation

- `core/engine/src/main/kotlin/com/poyka/ripdpi/core/Tun2SocksTunnel.kt:80-180`
- line 155: `stats()` holds `mutex` across `getStats` JNI call.
- line 168: `telemetry()` holds `mutex` across `getTelemetry` JNI call.
- line 133: `stop()` waits on the same mutex.

ADR: `docs/architecture/jni-handle-lifetime-telemetry-lock.md` ("Sibling Wrapper Audit").

## Acceptance criteria

- [ ] `Tun2SocksTunnel` uses `HandleReservation`; `mutex` field removed.
- [ ] Concurrent `stats()` / `telemetry()` calls overlap when bindings allow.
- [ ] `stop()` waits only for in-flight reservations to drain, not for arbitrary serialization.
- [ ] `start` create/destroy remain serialized against active reservations; existing CancellationException + destroy-on-failure semantics preserved.
- [ ] `Tun2SocksTunnelLockingTest` covers `telemetryDoesNotBlockStop`, `statsConcurrentWithTelemetry`, `stopWaitsForInFlightStats`.
- [ ] No JNI ABI change. `ripdpi-tunnel` cdylib unchanged.

## Blocker

Blocked on POY-175 (the `HandleReservation` primitive must land first).

## Stop gate

No protocol behavior, root-only, or live-network change. Non-rooted Android baseline preserved.

## Links

- [[Epic - Runtime lifecycle and supervisors]] (POY-56)
- POY-175 (primitive provider)
- ADR `docs/architecture/jni-handle-lifetime-telemetry-lock.md`
