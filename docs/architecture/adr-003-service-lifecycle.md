# ADR-003: Service Lifecycle Orchestration Boundary

**Status:** Proposed | 2026-04-15
**Deciders:** RIPDPI maintainers

## Context

Service lifecycle is currently spread across an inheritance chain:

- `ServiceRuntimeCoordinator.kt` (abstract base) holds restart policy,
  retry/backoff, permission watchdog integration, telemetry loop, and
  handover event handling via abstract/open methods.
- `VpnServiceRuntimeCoordinator.kt` and `ProxyServiceRuntimeCoordinator.kt`
  extend the base and override optional behaviours (VPN-specific tunnel
  bringup, resolver refresh, encrypted-DNS failover) by overriding open
  functions or adding fields conditionally checked at runtime.

The result is that optional behaviours (VPN resolver refresh, Warp enrollment,
screen-state observation) are scattered between the base class and subclasses
with no clear interface boundary. Adding a new service mode requires forking
the inheritance chain rather than composing existing pieces.

## Decision

Decompose `BaseServiceRuntimeCoordinator` into a fixed orchestrator that owns
stop/start command execution and restart/backoff policy, composed with
injected policy objects for each optional concern.

`core/service/src/main/kotlin/com/poyka/ripdpi/services/ServiceRuntimeCoordinator.kt`
is the single owner of lifecycle sequencing. All optional behaviours are
expressed as injected interfaces, not override points.

## Rationale

Composition over inheritance prevents the combinatorial explosion of subclass
variants. Each concern (restart policy, handover events, retry/backoff,
permission watchdog, telemetry loop) becomes a named interface with a default
no-op implementation. `VpnServiceRuntimeCoordinator` and
`ProxyServiceRuntimeCoordinator` become thin configuration sites that
assemble the correct set of policy objects for their mode, rather than
classes that override base behaviour. This matches the existing pattern
already used for `UpstreamRelaySupervisor`, `ProxyRuntimeSupervisor`, and
`WarpRuntimeSupervisor`, which are already injected rather than inherited.

Responsibility assignment under this model:
- Restart policy: `RestartPolicy` interface, owned by orchestrator.
- Handover events: `HandoverEventPolicy` interface, owned by orchestrator.
- Retry/backoff: `RetryBackoffPolicy` interface, owned by orchestrator.
- Permission watchdog: `PermissionWatchdog` (already injected), unchanged.
- Telemetry loop: `ServiceTelemetryLoop` interface, owned by orchestrator.
- Stop/start command execution: `ServiceRuntimeCoordinator` exclusively.

## Consequences

Positive:
- New service modes compose existing policies without subclassing.
- Each policy interface is independently testable.
- `BaseServiceRuntimeCoordinatorTest` becomes a suite of policy-contract tests
  rather than a catch-all for inherited behaviour.

Negative:
- Existing subclasses must be refactored to pass policy objects at construction
  time; this is a non-trivial migration of `VpnServiceRuntimeCoordinator`
  (~350 lines of override logic).
- Constructor parameter count of the orchestrator increases until a builder or
  factory is introduced.

## Owner

`core/service/src/main/kotlin/com/poyka/ripdpi/services/ServiceRuntimeCoordinator.kt`
