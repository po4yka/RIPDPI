# Decouple JNI Handle-Lifetime and Telemetry Locking

Status: Approved (design only; implementation gated behind the release-readiness queue). Decision date: 2026-05-02. Last revised: 2026-05-02 (post-audit scope widening).

## Decision

Replace the single `kotlinx.coroutines.sync.Mutex` per native runtime wrapper (`RipDpiProxy`, `RipDpiRelay`) with a two-region locking model:

1. a **lifetime region** that serializes `create`/`destroy` against everything else and owns the `handle: Long` field;
2. an **active-handle region** that admits concurrent telemetry / config-update JNI calls while `destroy` is forbidden, using a counted-handle reservation.

Lifecycle operations (`startProxy`, `stopProxy`, `start`, `stop`) keep their existing serialization guarantees: while a lifetime transition is in progress, no new active-handle reservation is admitted, and existing reservations drain before the transition mutates the handle. Telemetry and snapshot updates against a live handle no longer head-of-line-block each other or block lifecycle transitions for any longer than the in-flight JNI call already requires.

## Context

Today both `RipDpiProxy` (`core/engine/.../RipDpiProxy.kt:121-300`) and `RipDpiRelay` (`core/engine/.../RipDpiRelay.kt:195-345`) hold a single `Mutex` for the duration of every handle-touching JNI call:

- `withActiveHandle(...)` (proxy) acquires the mutex around `pollTelemetry` and `updateNetworkSnapshot`.
- `pollTelemetry` (relay) holds the mutex across the JNI poll.
- `stopProxy` / relay `stop()` acquire the mutex to read or null the handle.

Two consequences:

1. A long-running `pollTelemetry()` JNI call serializes a concurrent `updateNetworkSnapshot()` and a concurrent lifecycle `stop()`.
2. `stopProxy` / relay `stop()` cannot proceed until in-flight telemetry returns, even though native `stop()` is the operation the operator is waiting on.

Both wrappers already use `@Volatile private var handle = 0L`, so the address of the handle is publishable without the mutex. The mutex's only correctness job is making sure `destroy` does not retire a handle while another JNI call is still using it. That is a classic readers-writer problem; collapsing it into a single `Mutex` is what causes the head-of-line block.

A prior change has already moved telemetry to typed results, so the JNI surface is stable for the duration of this change.

## Options Considered

1. **Two-lock model with counted-handle reservation (chosen).** `lifetimeMutex` plus `activeReservations: AtomicInteger` plus an `idleSignal: CompletableDeferred<Unit>`. Active calls reserve, run JNI without holding the lifetime mutex, release. Destroy acquires the lifetime mutex, sets a "draining" flag that rejects new reservations, awaits `idleSignal`, then mutates the handle.

2. **Coroutine ReadWriteMutex.** Roll a small ReadWriteMutex (no stdlib) around the existing `Mutex`. Equivalent semantics to option 1 but more bespoke locking primitive surface; harder to reason about cancellation.

3. **Move all lifecycle into the native side and expose only async events.** Out of scope for this slice; would invalidate the JNI wrapper contract that was previously stabilized and crosses subsystem boundaries beyond what this issue covers.

4. **Per-method mutexes.** Would split lifecycle from telemetry but reintroduce the same serialization between concurrent telemetry / snapshot updates without solving the head-of-line block.

Chose option 1: smallest behavior delta against existing contracts, retains a single lifecycle mutex (so existing `readinessSignal` accounting needs no restructure), and the reservation primitive can be unit-tested in isolation through the existing `RipDpiProxyBindings` / `RipDpiRelayBindings` fakes.

## Chosen Approach

**1. Reservation primitive.** Introduce `core/engine/src/main/kotlin/com/poyka/ripdpi/core/lifetime/HandleReservation.kt` encapsulating:

```
class HandleReservation {
    suspend fun <T> withReservation(block: suspend () -> T): T
    suspend fun <T> withExclusive(block: suspend () -> T): T
    fun isDraining(): Boolean
}
```

Semantics:

- `withReservation` rejects with `NativeError.NotRunning(...)` when draining is set or the handle is `0L`; otherwise increments an in-flight counter, invokes `block`, decrements on completion (including cancellation).
- `withExclusive` acquires the lifetime mutex, sets `draining = true`, awaits in-flight count == 0 via a `CompletableDeferred`, runs `block`, clears `draining`, releases. The deferred is recreated per drain to make cancellation safe.
- The handle field stays on the runtime wrapper; the reservation primitive knows nothing about JNI.

**2. Wrapper integration.**

- `withActiveHandle` becomes `reservation.withReservation { jniCall(handle) }` and reads the handle via the same volatile field.
- `startProxy`/`stopProxy`, relay `start`/`stop` use `reservation.withExclusive { ... }` for the lifetime sections.
- `awaitReady` continues to read `readinessSignal` under the lifetime mutex (low contention; signal lives outside the JNI path).

**3. Cancellation.** Reservations release on cancellation via a `try/finally` inside `withReservation`. `withExclusive` does not cancel mid-drain; if the caller is cancelled while waiting for in-flight to drain, the drain completes anyway (counter is owned by reservations, not the caller).

**4. Telemetry recording (deferred).** The acceptance criteria call for "telemetry calls no longer block lifecycle operations (measured)". Add a gauge in `NativeRuntimeSnapshot` or an internal counter — design choice deferred to implementation; it MUST NOT change the existing telemetry JSON contract.

## Targeted Verification (named tests)

Implementation must land all of the following before the issue is closed. File paths name the canonical location; create files where missing.

| Test | File | Asserts |
|---|---|---|
| `HandleReservationTest#concurrentReservationsRunInParallel` | `core/engine/src/test/kotlin/com/poyka/ripdpi/core/lifetime/HandleReservationTest.kt` | Two concurrent `withReservation` calls overlap (no head-of-line block). |
| `HandleReservationTest#exclusiveDrainsInFlight` | (same) | `withExclusive` waits for all `withReservation` to release before its block runs. |
| `HandleReservationTest#newReservationsRejectedWhileDraining` | (same) | New `withReservation` calls throw `NativeError.NotRunning(...)` while a drain is in progress. |
| `HandleReservationTest#cancelledReservationReleases` | (same) | Cancelling the caller of `withReservation` decrements the in-flight counter and lets `withExclusive` complete. |
| `RipDpiProxyLockingTest#telemetryDoesNotBlockStop` | `core/engine/src/test/kotlin/com/poyka/ripdpi/core/RipDpiProxyLockingTest.kt` | A long-running fake `pollTelemetry` does not delay `stopProxy` past the in-flight JNI call's natural duration. Asserted with a fake `RipDpiProxyBindings` that suspends and a measured deadline. |
| `RipDpiProxyLockingTest#updateNetworkSnapshotConcurrentWithTelemetry` | (same) | Concurrent `updateNetworkSnapshot` and `pollTelemetry` overlap when the underlying bindings allow it. |
| `RipDpiProxyLockingTest#stopProxyWaitsForInFlightTelemetry` | (same) | `stopProxy` does not retire the handle until `pollTelemetry` returns; bindings receive `stop` only after `pollTelemetry` exit. |
| `RipDpiProxyLockingTest#startThenStopReusesHandle` | (same) | After `stopProxy`, a subsequent `startProxy` succeeds and acquires a new handle. |
| `RipDpiRelayLockingTest#telemetryDoesNotBlockStop` | `core/engine/src/test/kotlin/com/poyka/ripdpi/core/RipDpiRelayLockingTest.kt` | Mirrors the proxy test for `RipDpiRelay`. |
| `RipDpiRelayLockingTest#stopWaitsForInFlightTelemetry` | (same) | Mirrors the proxy test. |
| `RipDpiProxySupervisorLifecycleTest#vpnServiceCoordinatorRestartObservesIdle` | `core/service/src/test/kotlin/com/poyka/ripdpi/services/RipDpiProxySupervisorLifecycleTest.kt` (new) | The service runtime coordinator's restart path does not encounter the old head-of-line block: a fake telemetry that suspends 200ms does not push restart latency above 250ms. |

Existing tests that must continue to pass unchanged:

- `RipDpiProxyJsonCodecTest` (JSON contract for native config).
- `RipDpiProxyPreferencesTest` (preferences round-trip).
- `RipDpiVpnServiceConfigTest` (VPN service config).

JNI surface is unchanged; no native or `ripdpi-android*` adapter crate edits are required, and the JNI symbol diff guard must remain green.

## Rationale

1. **Smallest delta.** Only `RipDpiProxy.kt`, `RipDpiRelay.kt`, and the new `lifetime/HandleReservation.kt` are touched in Kotlin. No JNI ABI change, no protobuf/catalog change, no Rust workspace change.
2. **Acceptance-criteria coverage.** Each acceptance bullet maps to a named test above: - "Separate locks" → `HandleReservation` primitive + integration tests. - "Telemetry calls no longer block lifecycle operations (measured)" → `telemetryDoesNotBlockStop` assertions on both wrappers and the supervisor lifecycle test. - "Lifetime transitions remain serialized" → `exclusiveDrainsInFlight` and `stopProxyWaitsForInFlightTelemetry`. - "No new correctness regressions" → existing JsonCodec / Preferences / VpnService tests remain green.
3. **Cancellation safety.** Coroutine cancellation on the in-flight JNI call still releases the reservation, so a cancelled scope cannot wedge `withExclusive`.
4. **Maintains existing telemetry contract.** `pollTelemetry` returns the same `NativeRuntimeSnapshot`; the lock change is internal.
5. **Non-rooted baseline preserved.** No root-only path is touched; the change is purely about Kotlin coroutine concurrency, not protocol or platform behavior.

## Impacted Subsystems

- `core/engine` (Kotlin): adds `lifetime/HandleReservation.kt`, edits `RipDpiProxy.kt`, `RipDpiRelay.kt`. Sibling wrappers (`RipDpiWarp.kt`, `Tun2SocksTunnel.kt`, `NetworkDiagnostics.kt`) adopt the same primitive in follow-up issues — see "Sibling Wrapper Audit" below.
- `core/service` (Kotlin): new lifecycle parity test only; runtime coordinator behavior is unchanged.
- Rust workspace: untouched.
- JNI surface: untouched.
- Diagnostics catalog: untouched.

## Sibling Wrapper Audit

Audited 2026-05-02. Three additional wrappers exhibit the same single-mutex head-of-line pattern. Each is filed as a follow-up so the reservation primitive lands once and is reused.

| Wrapper | File | Head-of-line block | Severity | Follow-up |
|---|---|---|---|---|
| `RipDpiWarp` | `core/engine/.../RipDpiWarp.kt:200-377` | `pollTelemetry` (line 360) holds the mutex across the JNI call; `stop()` (line 344) waits for it. Same shape as `RipDpiRelay`. | Medium — VPN tunnel teardown latency. | tracked in task board |
| `Tun2SocksTunnel` | `core/engine/.../Tun2SocksTunnel.kt:80-180` | `stats()` and `telemetry()` (lines 155, 168) hold the mutex across JNI calls; `stop()` (line 133) waits for them. Both `stats` and `telemetry` are on the supervisor hot poll path. | Medium — supervisor hot path. | tracked in task board |
| `NetworkDiagnostics` | `core/engine/.../NetworkDiagnostics.kt:99-165` | `pollProgressJson` / `takeReportJson` / `pollPassiveEventsJson` (lines 136-152) hold the mutex across JNI calls; `cancelScan()` (line 128) waits for them. | High — `cancelScan` is the operator-visible "abort scan" path; latency directly affects perceived responsiveness. | tracked in task board |

Migration approach for siblings:

- The implementer lands `HandleReservation` in `core/engine/.../lifetime/` with no public API beyond the primitive and its tests.
- Each follow-up issue (one per sibling wrapper) replaces the local `mutex` with the primitive and adds wrapper-specific locking tests named in the same `*_LockingTest` style.
- All sibling migrations are non-rooted-baseline-preserving and JNI-ABI-preserving by construction.

Out of scope for this ADR and any follow-up: protocol behavior, telemetry JSON contract, root-only paths, live-network behavior.

## Risks

| Risk | Mitigation |
|---|---|
| Reservation primitive bug deadlocks `withExclusive`. | `HandleReservationTest` covers drain semantics, including cancellation. Fake bindings in wrapper tests simulate slow JNI calls. |
| Concurrent telemetry triggers a native data race on the handle. | The handle itself stays single-writer (only `withExclusive` mutates it). Active reservations only call JNI methods that take the handle as input; the native side is already expected to be reentrant. |
| Behavior change in `awaitReady` (still grabs lifetime mutex). | Test suite asserts `awaitReady` continues to terminate on `runtime_ready` events without altered timeout semantics. |
| Future JNI calls that mutate global native state slip in under `withReservation` and break the assumption that only the handle is in play. | Add a code-review gate: any new `RipDpi*Bindings` method that does not take `handle` must use `withExclusive`. Captured in this ADR's `Required reviews`. |

## Required Reviews

- Lock primitive design, coroutine cancellation.
- Confirm native side already supports reentrant telemetry/config calls.
- Lifecycle parity test plan in `core/service`.
- Confirm no privacy or telemetry surface change; pure internal lock refactor.

## Verification Requirements

- All targeted tests above land and pass on CI.
- `./gradlew :core:engine:test :core:service:test` green.
- JNI symbol diff guard unchanged.
- No detekt/lint baseline extension. Any violation must be fixed at source per project policy.
- `./gradlew --configuration-cache help` continues to hit cache.

## Follow-Up Tasks

- After implementation, file a short "ADR amendment" only if the reservation primitive needs to grow a third state (e.g. paused). Do not amend in place.
- Optional later slice: extend the same primitive to other native wrappers if any new ones appear (none today).

## Implementation Sequencing

This ADR is design-only. When the release-readiness queue clears:

1. Implement `HandleReservation` primitive plus tests first (single PR).
2. Implement wrapper integration plus integration tests second (single PR).
3. Confirm supervisor lifecycle parity test green.

Until then, no source edits to `RipDpiProxy.kt` or `RipDpiRelay.kt`.
