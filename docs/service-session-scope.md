# Service Session Scope

`RipDpiProxyService` and `RipDpiVpnService` now create explicit Hilt session components in `onCreate()` and resolve their per-run graph from those components.

## Components

- `ProxyServiceSessionComponent`
- `VpnServiceSessionComponent`
- `BootstrapProxySessionComponent`

Each component is rooted in `SingletonComponent`, but it owns only session-lifetime objects.

## Session-Owned Objects

- service runtime coordinators
- runtime supervisors (`UpstreamRelaySupervisor`, `WarpRuntimeSupervisor`, `ProxyRuntimeSupervisor`)
- `ServiceStatusReporter`
- VPN-only runtime helpers such as `VpnTunnelRuntime`, `VpnEncryptedDnsFailoverController`, and `VpnProtectSocketServer`
- the transient proxy supervisor used for WARP bootstrap provisioning

## Singleton-Owned Objects

These stay in `SingletonComponent` because they are shared across runs:

- stores and repositories
- fingerprint and policy providers
- runtime registries and app-wide observers
- factories for runtime subprocesses

## Lifecycle Model

The Android service creates the session component once per service instance and keeps a reference to it for the lifetime of that service instance.

- `onCreate()`: build the session component and resolve the session-owned graph
- `start()/stop()`: coordinators manage runtime start and shutdown within the session
- `onDestroy()`: the service releases the session component reference after coordinator cleanup

`ManagedWarpBootstrapProxyRunner` uses its own short-lived bootstrap session component per provisioning run, bound to a child coroutine scope that is cancelled when the transient proxy stops.

This keeps per-run objects grouped behind one lifetime boundary and removes the old pattern where the service manually assembled its runtime graph from singleton-backed factories.
