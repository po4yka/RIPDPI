# Implementation Plan

## Checklist

- [ ] Step 1: Lock the current service contract with characterization tests.
- [ ] Step 2: Introduce an explicit runtime state model and transition helpers without changing behavior.
- [ ] Step 3: Extract active policy application and policy-derived telemetry context.
- [ ] Step 4: Extract resolver refresh planning and rebuild execution behind tests.
- [ ] Step 5: Extract proxy and tunnel lifecycle coordination under one serialized owner.
- [ ] Step 6: Extract telemetry polling and failure translation.
- [ ] Step 7: Extract network handover monitoring and recovery.
- [ ] Step 8: Slim the service to lifecycle and platform glue only.

## Step 1: Lock the current service contract with characterization tests

Objective:
Define the behavior that must survive the refactor before any structural extraction begins.

Implementation guidance:
Extend the existing integration-style service tests and service-adjacent tests so they explicitly describe the current contract around:

- duplicate start and stop behavior
- proxy exit during shutdown
- resolver override propagation and tunnel rebuild behavior
- handover restart behavior and cooldown behavior
- telemetry-loop failure behavior

Test requirements:

- Add or strengthen characterization tests before changing production structure.
- Reuse `IntegrationTestOverrides` and existing service integration harnesses where possible.
- Prefer service-observable assertions over internal field assertions.

Integrates with previous work:
This is the safety gate for the entire refactor. No runtime extraction should begin until these tests pass and document the current contract.

Demo:
The codebase still behaves exactly as it does today, but the high-risk lifecycle behavior is now explicitly covered by tests.

## Step 2: Introduce an explicit runtime state model and transition helpers without changing behavior

Objective:
Replace the current cluster of mutable service fields with an explicit runtime state representation and small transition helpers.

Implementation guidance:
Create `VpnRuntimeState` and any helper value types needed to represent current runtime ownership, counters, cooldown state, and pending handover metadata. Keep the service behavior unchanged, but route internal reads and writes through the new state model.

Test requirements:

- Add unit tests for state transition helpers, especially stop-reset semantics and one-shot pending handover classification consumption.
- Keep Step 1 characterization tests green while migrating field access.

Integrates with previous work:
This step reduces hidden coupling and makes later collaborator extraction safer.

Demo:
The service still starts and stops exactly the same way, but its mutable runtime state is now explicit and easier to reason about in tests.

## Step 3: Extract active policy application and policy-derived telemetry context

Objective:
Move policy-store mutation and winning-family selection out of the service.

Implementation guidance:
Extract an `ActivePolicyCoordinator` or equivalent collaborator that:

- applies `ActiveConnectionPolicyStore` updates from `ConnectionPolicyResolution`
- clears active policy state when needed
- provides the current winning strategy family inputs for telemetry enrichment

Test requirements:

- Add unit tests for initial-start vs handover restart metadata.
- Add unit tests for clearing behavior when no applied policy exists.
- Preserve service-level telemetry and policy behavior through existing integration tests.

Integrates with previous work:
This is a low-risk extraction that reduces service responsibility without yet moving runtime lifecycle sequencing.

Demo:
Policy application behavior remains unchanged, but the service no longer contains policy-store mutation details.

## Step 4: Extract resolver refresh planning and rebuild execution behind tests

Objective:
Move resolver override refresh behavior behind a dedicated collaborator and lock its behavior with tests before touching runtime restart structure.

Implementation guidance:
Build a `ResolverRefreshCoordinator` around the existing DNS-resolution helpers. Keep the double-check pattern intact:

- speculative plan outside the critical section
- guarded re-check inside the serialized transition

The collaborator should return a decision that clearly states whether to clear the override and whether tunnel rebuild is required.

Test requirements:

- Add unit tests for resolver refresh decisions using current `VpnResolverRuntime` rules.
- Add service-level contract tests proving that tunnel rebuild happens only when the effective DNS signature changes and the tunnel is running.
- Add tests proving redundant overrides are cleared.

Integrates with previous work:
This step removes one of the more fragile side loops from the service before extracting the larger lifecycle coordinator.

Demo:
Resolver override behavior and tunnel rebuild semantics remain stable, but refresh logic is no longer embedded in the service.

## Step 5: Extract proxy and tunnel lifecycle coordination under one serialized owner

Objective:
Move proxy and tunnel start and stop orchestration into a dedicated lifecycle coordinator while preserving ordering and cleanup behavior.

Implementation guidance:
Introduce a `ProxyTunnelLifecycleCoordinator` that handles:

- proxy start and ready wait
- proxy exit callback hookup
- tunnel config creation
- VPN session establishment through a service-provided platform adapter
- tunnel stop and session close

Keep `VpnRuntimeCoordinator` as the only owner of the mutex and the only caller that decides when lifecycle operations run.

Test requirements:

- Add unit tests for proxy-start failure cleanup, tunnel-start failure cleanup, and ordered shutdown.
- Add contract tests for proxy-exit behavior during normal failure vs explicit stop.
- Keep all Step 1 characterization tests green.

Integrates with previous work:
By this point the service should no longer own most runtime handles directly.

Demo:
Runtime startup and shutdown still behave the same from the outside, but proxy and tunnel orchestration now live outside the service.

## Step 6: Extract telemetry polling and failure translation

Objective:
Move telemetry-loop behavior into a dedicated collaborator without changing emitted snapshots or failure semantics.

Implementation guidance:
Create `VpnTelemetryCoordinator` to:

- poll proxy and tunnel telemetry
- enrich snapshots with policy and handover context
- classify telemetry-driven failures
- report failure outcomes back to the runtime coordinator

Do not let the telemetry collaborator stop the service directly. It should surface decisions to the runtime coordinator.

Test requirements:

- Add unit tests for idle fallback behavior, unexpected tunnel-stop detection, and failure classification propagation.
- Add tests that `pendingNetworkHandoverClass` is applied once and only once.
- Preserve service-level telemetry characterization coverage.

Integrates with previous work:
This step completes extraction of the most repetitive runtime loop while keeping transition ownership centralized.

Demo:
Telemetry snapshots and failure behavior remain unchanged, but the service no longer owns the polling loop.

## Step 7: Extract network handover monitoring and recovery

Objective:
Move handover subscription, cooldown handling, policy re-resolution, in-place restart, and event publication out of the service.

Implementation guidance:
Create `NetworkHandoverRecoveryCoordinator` that consumes `NetworkHandoverMonitor` events and works with `VpnRuntimeCoordinator` for serialized restart execution. Cooldown fields should live in the runtime state, not in the service.

Test requirements:

- Add unit tests for actionability filtering, cooldown suppression, and event-publication conditions.
- Add contract tests for in-place restart ordering and failure fallback to full stop.
- Add or strengthen service-level tests for successful handover restart and cooldown suppression.

Integrates with previous work:
This step removes the last major runtime orchestration block from the service.

Demo:
Network handover recovery continues to work the same way, but the service is no longer directly running the recovery algorithm.

## Step 8: Slim the service to lifecycle and platform glue only

Objective:
Finish the refactor by leaving `RipDpiVpnService` as a thin Android boundary class.

Implementation guidance:
Reduce the service to:

- intent handling
- foreground notification setup
- `onRevoke()` delegation
- `VpnService.Builder` and notification glue
- wiring to runtime coordinator and platform adapter

Remove obsolete mutable runtime fields from the service once the extracted collaborators fully own them.

Test requirements:

- Keep the full characterization suite green.
- Add lightweight contract tests proving the service delegates start, stop, and revoke to the coordinator.
- Use instrumentation only for platform-specific behavior that still needs Android coverage.

Integrates with previous work:
This step is the final consolidation once all risky runtime responsibilities have already been extracted behind tests.

Demo:
`RipDpiVpnService` becomes a focused Android service wrapper, while runtime orchestration lives in dedicated collaborators and existing behavior remains intact.

## Execution Notes

- Do not combine Steps 4 through 7 into one large extraction. Each one moves a high-risk behavior and needs green tests before continuing.
- Keep the current integration harnesses alive during the refactor rather than replacing them.
- Favor small delegation commits that preserve a runnable, demoable service after every step.
