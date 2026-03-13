# Requirements

## Overview

This document defines the requirements for a planning-only refactor of `core/service/src/main/java/com/poyka/ripdpi/services/RipDpiVpnService.kt`.

The purpose of the refactor is to narrow the Android service to true service responsibilities while extracting runtime collaborators in a way that preserves existing lifecycle semantics, shutdown behavior, recovery behavior, and telemetry output.

## Verified Current Responsibilities

The current service is responsible for all of the following, and the plan must account for each one explicitly:

1. Lifecycle handling through `onCreate`, `onStartCommand`, and `onRevoke`.
2. Foreground notification setup and Android `VpnService` platform glue.
3. Proxy startup, ready wait, exit handling, and proxy shutdown.
4. Tunnel configuration, `VpnService.Builder` usage, tunnel startup, and tunnel shutdown.
5. Telemetry polling, enrichment, state-store updates, and failure detection.
6. Network handover monitoring, cooldown checks, restart sequencing, and event publishing.
7. Resolver override evaluation, clearing, and tunnel rebuild decisions.
8. Active policy application and policy-derived telemetry enrichment.
9. Service-state broadcasting and failure emission.

## Refactor Goal

The resulting design must leave `RipDpiVpnService` responsible only for:

- Android lifecycle entry points.
- Foreground-service setup and teardown.
- `VpnService` platform glue such as notification wiring and `Builder` construction.
- Delegation of runtime work to injected collaborators.

The resulting design must move runtime orchestration out of the service into explicit collaborators for:

- Proxy and tunnel lifecycle coordination.
- Telemetry update logic.
- Resolver override handling.
- Network handover monitoring and recovery.
- Policy application and policy-derived telemetry context.

## Functional Requirements

1. The service must remain startable through `START_ACTION` and stoppable through `STOP_ACTION`.
2. `onRevoke()` must still trigger the same shutdown path as an explicit stop.
3. The refactor must preserve the current start order: foreground first, then resolution and runtime startup.
4. The refactor must preserve the current shutdown order: stop monitoring, stop tunnel, close VPN session, stop proxy, clear stores, update disconnected status, stop self.
5. Duplicate start attempts while already connected must remain benign and must not launch a second runtime.
6. Duplicate stop attempts must remain benign and must not duplicate proxy or tunnel shutdown.
7. Proxy startup failure, tunnel startup failure, VPN establish failure, unexpected proxy exit, and unexpected tunnel exit must still produce the same observable failure outcomes.
8. Temporary resolver overrides must still take precedence over persisted settings until they become redundant, and tunnel runtime must reflect the effective resolver.
9. Resolver refresh must still rebuild the tunnel only when the effective DNS signature actually changes while the tunnel is running.
10. Network handover handling must still use actionable events only, honor the cooldown window, re-resolve policy against the current fingerprint, restart the runtime in place, and publish a handover event on success.
11. Active connection policy store updates must still reflect initial start and handover restart reasons.
12. Telemetry updates must still publish proxy and tunnel snapshots, preserve winning strategy family metadata, and classify failures the same way.
13. All existing Android-visible behavior around `VpnService.Builder` configuration must remain unchanged.

## Non-Functional Requirements

1. Concurrency ownership must become explicit. Runtime state must have one clear owner, rather than being implicitly shared across service methods and jobs.
2. State transitions must remain serialized with a clearly defined locking strategy.
3. Collaborator interfaces must make lifecycle-sensitive interactions easy to test without requiring Android instrumentation for every case.
4. The service class should materially shrink in responsibility and mutable state ownership.
5. The refactor should prefer extracting cohesive runtime collaborators over introducing a wide set of thin pass-through wrappers.

## Hard Invariants

These invariants must be preserved throughout the refactor:

- `startForeground()` is called before long-running startup work.
- Proxy exit during an explicit stop does not generate a second failure path.
- Tunnel teardown always closes the VPN session even if tunnel stop throws.
- Full stop clears resolver override state and active connection policy state.
- Service failure emits a `Sender.VPN` failure event through `ServiceStateStore`.
- `pendingNetworkHandoverClass` is applied at most once to telemetry/status snapshots.
- Network handover recovery does not re-run for the same fingerprint inside the cooldown window.
- Telemetry polling stops once service status is no longer connected.

## Risky Lifecycle And Concurrency Areas

The plan must treat the following areas as high risk and require tests before major extraction:

1. Runtime handle ownership: `ripDpiProxy`, `proxyJob`, `tun2SocksBridge`, `tunSession`, and the jobs are currently mutated from multiple code paths.
2. Stop and restart coordination: the `stopping` flag is used to suppress proxy-exit handling and to protect in-place handover restart.
3. Proxy completion callback: `invokeOnCompletion` can race with explicit stop or restart.
4. Resolver refresh: it combines speculative reads outside the mutex with guarded re-checks inside the mutex.
5. Telemetry loop failure path: tunnel telemetry failures transition state and trigger stop from inside the polling loop.
6. Handover recovery: restart work happens under the service mutex while also publishing post-restart events.
7. Store-reset semantics: resolver override store and active policy store are cleared in `stop()` `finally`, not in a separate cleanup callback.

## Testing Requirements

The implementation plan must be test-first and must use the following test mix:

1. Characterization tests for service-level observable behavior where the current service already exposes a stable contract.
2. New unit tests for extracted coordinators, planners, and policy-application logic.
3. Contract tests for lifecycle-driven interactions between the service and collaborators.
4. Integration or instrumentation-style tests only where Android `Service` or `VpnService` behavior requires it.

The plan must add or strengthen coverage before extracting the following areas:

- Runtime start and stop sequencing.
- Proxy exit behavior.
- Resolver refresh behavior.
- Network handover restart behavior.
- Telemetry-loop failure behavior.

## Out Of Scope

- Changing business rules for policy resolution.
- Changing VPN routing, addressing, or exclusion behavior.
- Changing telemetry schema or failure-class semantics.
- Reworking unrelated services or ongoing refactor documentation elsewhere in the repo.
