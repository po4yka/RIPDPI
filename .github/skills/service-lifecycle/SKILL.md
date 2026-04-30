---
name: service-lifecycle
description: VPN/proxy service lifecycle, coordinators, status, telemetry, handovers, and remembered policy.
---

# Service Lifecycle

## Overview

The Android services are thin shells now. Real lifecycle behavior lives in runtime coordinators plus `ServiceStatusReporter`, not in a single global app-state singleton.

Service work in this repo usually touches one or more of:

- start/stop orchestration
- remembered-policy replay
- handover-triggered restarts
- active-policy signature tracking
- `ServiceStateStore` status, events, and telemetry
- hidden first-seen-network `quick_v1` diagnostics probes after successful handovers

## Architecture

```text
ServiceController.start(mode)
  -> START_ACTION / STOP_ACTION intent
  -> RipDpiVpnService or RipDpiProxyService
  -> ProxyServiceRuntimeCoordinator / VpnServiceRuntimeCoordinator
  -> ConnectionPolicyResolver
  -> proxy / tunnel runtime start-stop-restart
  -> ServiceStatusReporter
  -> ServiceStateStore
```

## Key Components

| Component | Location | Role |
|-----------|----------|------|
| `ServiceController` / `DefaultServiceController` | `core/service/.../ServiceManager.kt` | App entry point for start/stop |
| `RipDpiVpnService` | `core/service/.../RipDpiVpnService.kt` | Android VPN service shell |
| `RipDpiProxyService` | `core/service/.../RipDpiProxyService.kt` | Android foreground proxy service shell |
| `BaseServiceRuntimeCoordinator` | `core/service/.../ServiceRuntimeCoordinator.kt` | Shared mutex, lifecycle state machine, stop/start/handover flow |
| `ProxyServiceRuntimeCoordinator` | `core/service/.../ProxyServiceRuntimeCoordinator.kt` | Proxy-mode runtime orchestration |
| `VpnServiceRuntimeCoordinator` | `core/service/.../VpnServiceRuntimeCoordinator.kt` | VPN-mode proxy+tunnel orchestration, resolver refresh, DNS failover |
| `ServiceStatusReporter` | `core/service/.../ServiceStatusReporter.kt` | Maps runtime changes into UI-facing status/events/telemetry |
| `ServiceStateStore` | `core/data/.../ServiceStateStore.kt` | Shared `StateFlow` / `SharedFlow` store observed by the app |
| `ConnectionPolicyResolver` | `core/service/.../ConnectionPolicyResolver.kt` | Resolves live or remembered policy for the current network |
| `NetworkHandoverMonitor` | `core/service/.../NetworkHandoverMonitor.kt` | Detects actionable network changes |
| `ActiveConnectionPolicyStore` | `core/service/.../ActiveConnectionPolicyStore.kt` | Persists active policy metadata for diagnostics and telemetry |

## State Management

```kotlin
interface ServiceStateStore {
    val status: StateFlow<Pair<AppStatus, Mode>>
    val events: SharedFlow<ServiceEvent>
    val telemetry: StateFlow<ServiceTelemetrySnapshot>

    fun setStatus(status: AppStatus, mode: Mode)
    fun emitFailed(sender: Sender, reason: FailureReason)
    fun updateTelemetry(snapshot: ServiceTelemetrySnapshot)
}
```

Runtime code should usually write through `ServiceStatusReporter`. That keeps `status`, `events`, and `telemetry` consistent.

## Lifecycle Pattern

Start/stop logic is serialized inside the coordinator mutex:

```kotlin
suspend fun start() {
    mutex.withLock {
        val resolution = resolveInitialConnectionPolicy()
        applyActiveConnectionPolicy(...)
        startResolvedRuntime(...)
        serviceRuntimeRegistry.register(session)
        updateStatus(ServiceStatus.Connected)
        startNetworkHandoverMonitoring()
        startModeTelemetryUpdates()
    }
}
```

The VPN coordinator adds:

- `VpnTunnelRuntime` startup and shutdown
- resolver refresh via `VpnResolverRefreshPlanner`
- encrypted-DNS recovery via `VpnEncryptedDnsFailoverController`

The proxy coordinator adds:

- `ProxyRuntimeSupervisor` startup and shutdown
- proxy telemetry polling
- proxy exit handling and failure classification

## Where To Make Changes

1. Change Android service classes only for Android-framework concerns such as intents, foreground-service behavior, or `onRevoke()`.
2. Change `ProxyServiceRuntimeCoordinator` or `VpnServiceRuntimeCoordinator` for runtime orchestration.
3. Change `ServiceStatusReporter` for UI-visible status, failure, or telemetry projection.
4. Change `ConnectionPolicyResolver`, `NetworkHandoverMonitor`, or resolver refresh logic when the behavior depends on network context.
5. Treat `ServiceTelemetrySnapshot` and `ServiceEvent` as compatibility-sensitive contracts used by tests, diagnostics, and exports.

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Patching only the Android service shell | Most lifecycle logic lives in the coordinator layer now |
| Writing directly to `ServiceStateStore` from arbitrary runtime code | Prefer `ServiceStatusReporter` so fields stay coherent |
| Ignoring the coordinator mutex or lifecycle state machine | Start/stop/handover paths must stay serialized |
| Forgetting remembered-policy or handover side effects | Service restarts affect policy replay, telemetry, and hidden diagnostics probes |
| Treating telemetry fields as disposable | `ServiceTelemetrySnapshot` is part of the repo’s compatibility surface |
