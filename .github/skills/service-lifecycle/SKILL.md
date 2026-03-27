---
name: service-lifecycle
description: Use when modifying VPN or proxy service logic, handling service start/stop lifecycle, debugging connection state issues, or working with AppStateManager
---

# Service Lifecycle

## Overview

RIPDPI runs either a VPN service or a plain proxy service. Both follow the same lifecycle pattern: intent-driven start/stop, Mutex-guarded state transitions, and centralized state broadcasting via `AppStateManager`.

Service changes often affect more than process lifetime. The same layer also coordinates:

- handover-triggered restarts
- remembered-policy replay
- active-policy signature tracking
- hidden first-seen-network `quick_v1` diagnostics probes after successful handovers

## Architecture

```
ServiceManager.start(context, mode)
    |
    v
Intent(START_ACTION) -> RipDpiVpnService or RipDpiProxyService
    |
    v
suspend start() [Mutex-guarded]
    -> startProxy() [launches IO coroutine]
    -> startTun2Socks() [VPN only: creates TUN device]
    -> AppStateManager.setStatus(Running, mode)
    -> startForeground(notification)
```

## Key Components

| Component | Location | Role |
|-----------|----------|------|
| `ServiceManager` | `core/service/.../ServiceManager.kt` | Singleton entry point for start/stop |
| `RipDpiVpnService` | `core/service/.../RipDpiVpnService.kt` | VPN mode: proxy + tun2socks |
| `RipDpiProxyService` | `core/service/.../RipDpiProxyService.kt` | Proxy mode: proxy only |
| `LifecycleVpnService` | `core/service/.../LifecycleVpnService.kt` | Base class bridging VpnService to Lifecycle |
| `AppStateManager` | `core/service/.../AppStateManager.kt` | Global state broadcast (StateFlow + SharedFlow) |
| `RipDpiProxy` | `core/engine/.../RipDpiProxy.kt` | Kotlin wrapper for native proxy |
| `ConnectionPolicyResolver` | `core/service/.../ConnectionPolicyResolver.kt` | Chooses live or remembered policy for the current network |
| `NetworkHandoverMonitor` | `core/service/.../NetworkHandoverMonitor.kt` | Classifies actionable network changes and schedules restarts |
| `ActiveConnectionPolicyStore` | `core/service/.../ActiveConnectionPolicyStore.kt` | Tracks the active policy signature/fingerprint for diagnostics and telemetry |

## State Management

```kotlin
// AppStateManager (singleton)
object AppStateManager {
    val status: StateFlow<Pair<AppStatus, Mode>>  // Running/Halted + VPN/Proxy
    val events: SharedFlow<ServiceEvent>           // Failed events

    fun setStatus(status, mode)
    fun emitFailed(sender)
}
```

The `MainViewModel` observes both `status` and `events` to drive UI state.

## Service Lifecycle Pattern

Both services follow this pattern:

```kotlin
class RipDpiXxxService : LifecycleService() {
    private val proxy = RipDpiProxy()
    private var proxyJob: Job? = null
    private val mutex = Mutex()
    private var stopping = false

    override fun onStartCommand(intent, flags, startId) = super.onStartCommand(...).also {
        when (intent?.action) {
            START_ACTION -> lifecycleScope.launch { start() }
            STOP_ACTION -> lifecycleScope.launch { stop() }
        }
    }

    private suspend fun start() = mutex.withLock {
        stopping = false
        startProxy()
        // VPN: also startTun2Socks()
        AppStateManager.setStatus(AppStatus.Running, mode)
        startForeground(NOTIFICATION_ID, notification)
    }

    private suspend fun stop() = mutex.withLock {
        stopping = true
        // VPN: stopTun2Socks() first
        stopProxy()
        AppStateManager.setStatus(AppStatus.Halted, mode)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
```

## VPN-Specific Details

- `startTun2Socks()`: Builds `Tun2SocksConfig`, establishes the TUN fd, then calls `Tun2SocksTunnel.start(config, tunFd)`
- VPN builder: address `10.10.10.10/32`, IPv6 `fd00::1/128`, excludes self from tunnel
- `onRevoke()`: Called when user revokes VPN permission -- triggers stop

## Adding Service Behavior

1. Add logic inside the `start()` or `stop()` suspend functions (inside `mutex.withLock`)
2. For new state: add to `AppStateManager` if UI needs to observe it
3. For new events: add variant to `ServiceEvent` sealed interface
4. For new service type: extend `LifecycleVpnService` or `LifecycleService`, follow the Mutex pattern
5. If a change affects policy replay, handovers, or hidden automatic probing, inspect `ConnectionPolicyResolver`, `NetworkHandoverMonitor`, and `core:diagnostics` together instead of patching the service in isolation

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Accessing fd without Mutex | All fd operations must be inside `mutex.withLock` |
| Forgetting to update AppStateManager | Every state transition must call `setStatus()` |
| Blocking on main thread | Use `lifecycleScope.launch` and `Dispatchers.IO` for proxy operations |
| Not handling `onRevoke()` | VPN service must gracefully stop when permission is revoked |
| Missing foreground notification | Service crashes on Android 8+ without `startForeground()` call |
| Starting service without checking stopping flag | Check `stopping` flag to prevent race between start and stop |
