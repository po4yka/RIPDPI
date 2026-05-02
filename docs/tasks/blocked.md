# Blocked — RIPDPI

> Tasks that cannot proceed until an external condition resolves.

- [ ] #task Decouple JNI handle-lifetime and telemetry locking #repo/RIPDPI #area/runtime-lifecycle-and-supervisors #status/blocked #blocked 🔼 [paperclip:POY-175]
  - Paperclip: POY-175 · assigned to: Principal Android Rust Architect
  - Parent: POY-56 (Epic - Runtime lifecycle and supervisors)
  - Blocks: POY-234, POY-285, POY-286, POY-287
  - Blocked by: POY-249
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-20
  - **area:** android
  - **tags:** task, feature, ripdpi, lifecycle, jni
  - **source:** `TaskNotes/Tasks/Decouple JNI handle-lifetime and telemetry locking.md`
  - **epic:** Epic - Runtime lifecycle and supervisors

  ## Summary

  `RipDpiProxy` and `RipDpiRelay` serialize all handle-sensitive JNI work
  behind a single mutex. Telemetry polls head-of-line-block lifecycle calls
  and vice versa.

  ## Audit citation

  - `core/engine/.../RipDpiProxy.kt:132-142,220-254,267-277`
  - `core/engine/.../RipDpiRelay.kt:192-318`

  ## Acceptance criteria

  - [ ] Separate locks: one for handle create/destroy (lifetime), one for
        ordinary telemetry/config updates against a live handle.
  - [ ] Telemetry calls no longer block lifecycle operations (measured).
  - [ ] Lifetime transitions remain serialized against all other handle use.
  - [ ] No new correctness regressions in existing tests.

  ## Links

  - [[Epic - Runtime lifecycle and supervisors]]
  - [[Add native readiness events to RipDpi wrappers]]
  - [[ripdpi-android-audit-2026-04-20]]

- [ ] #task Select resolver mapping from DNS classification #repo/RIPDPI #area/encrypted-dns-and-https #status/blocked #blocked ⏫ [paperclip:POY-234]
  - Paperclip: POY-234 · assigned to: Senior Network Protocol Engineer
  - Parent: POY-44 (Epic - Encrypted DNS and HTTPS SVCB classifier)
  - Blocked by: POY-175
  
  <!-- migrated from obsidian -->
  - **dateCreated:** 2026-04-20
  - **dateModified:** 2026-04-23
  - **area:** android
  - **tags:** task, feature, ripdpi, direct-mode, dns
  - **source:** `TaskNotes/Tasks/Select resolver mapping from DNS classification.md`
  - **epic:** Epic - Encrypted DNS and HTTPS SVCB classifier

  ## Summary

  Implement the resolver selection logic:

  ```
  if DNS_POISONED:
      use encrypted mapping immediately
  elif DNS_DIVERGENT and transport failures correlate with system answers:
      prefer encrypted mapping
  else:
      keep fastest resolver path
  ```

  ## Plan reference

  [[ripdpi-android-direct-mode-plan-2026-04-20]] §2 selection logic.

  ## Acceptance criteria

  - [ ] Selection runs after classification, produces a concrete
        `ResolvedMapping { best_ip, ip_family, source }`.
  - [ ] `DIVERGENT` correlation check uses observed transport fail phase,
        not a static heuristic.
  - [ ] On `CLEAN`, fastest resolver wins — no unnecessary encrypted-DNS
        overhead.
  - [ ] Selection is cached per `(host, NetProfile)` with the same TTL as
        the family cache.

  ## Implementation note

  As of 2026-04-23, RIPDPI now consumes the classifier-derived
  `DOH_PRIMARY` / `DOH_SECONDARY` signal in two enforcement paths:
  authority-scoped native hostname resolution and VPN startup when the
  observed hostname-backed hints converge on one resolver role. That lands the
  runtime resolver-mapping slice without yet implementing the richer
  `ResolvedMapping { best_ip, ip_family, source }` object or the dedicated
  `(host, NetProfile)` selection cache described above.

  ## Links

  - [[Classify DNS as clean poisoned divergent ech-capable]]
  - [[Epic - Encrypted DNS and HTTPS SVCB classifier]]
  - [[ripdpi-android-direct-mode-plan-2026-04-20]]

- [ ] #task Adopt HandleReservation primitive in RipDpiWarp #repo/RIPDPI #area/runtime-lifecycle-and-supervisors #status/blocked #blocked 🔼 [paperclip:POY-285]
  - Paperclip: POY-285 · assigned to: Principal Android Rust Architect
  - Parent: POY-56 (Epic - Runtime lifecycle and supervisors)
  - Blocked by: POY-175
  
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

- [ ] #task Adopt HandleReservation primitive in Tun2SocksTunnel #repo/RIPDPI #area/runtime-lifecycle-and-supervisors #status/blocked #blocked 🔼 [paperclip:POY-286]
  - Paperclip: POY-286 · assigned to: Principal Android Rust Architect
  - Parent: POY-56 (Epic - Runtime lifecycle and supervisors)
  - Blocked by: POY-175
  
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

- [ ] #task Adopt HandleReservation primitive in NetworkDiagnostics (cancelScan latency) #repo/RIPDPI #area/runtime-lifecycle-and-supervisors #status/blocked #blocked ⏫ [paperclip:POY-287]
  - Paperclip: POY-287 · assigned to: Principal Android Rust Architect
  - Parent: POY-56 (Epic - Runtime lifecycle and supervisors)
  - Blocked by: POY-175
  
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
