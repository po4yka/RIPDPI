# `:core:service` — Android VPN & proxy service module

The Android **foreground services** that run RIPDPI's VPN and proxy modes, plus
all of the runtime orchestration around them — lifecycle, connection-policy
resolution, DNS, relay supervision, telemetry, network handover, and the
diagnostics bridge. This module owns the long-lived process; the native Rust
runtime is reached through `:core:engine`.

This document is **descriptive** — it maps ownership boundaries so a reader can
find the right file. It changes no behavior.

Companion docs: [`docs/architecture/ARCHITECTURE.md`](../../docs/architecture/ARCHITECTURE.md),
[`RUNTIME_MODES.md`](../../docs/architecture/RUNTIME_MODES.md),
[`ROOT_HELPER_CONTRACT.md`](../../docs/architecture/ROOT_HELPER_CONTRACT.md),
[`TELEMETRY_CONTRACT.md`](../../docs/architecture/TELEMETRY_CONTRACT.md),
[`.claude/rules/android-vpn-lifecycle.md`](../../.claude/rules/android-vpn-lifecycle.md).

---

## Module shape — `service` vs `services`

The Kotlin source is split across two sibling packages under
`com.poyka.ripdpi`. The distinction is **load-bearing** — keep new code on the
correct side:

| Package | Role |
|---------|------|
| `service.*` | The **coordinator / DI-wiring layer**. Hilt `@Module`s (`service.session.{proxy,vpn}`) and the per-mode runtime coordinators (`service.runtime.{proxy,vpn}`) that *compose* implementations into a coherent runtime. Small, organized, sub-packaged. |
| `services.*` | The **implementation layer** — flat files plus organized sub-packages (`services.dns`, `.leak`, `.lifecycle`, `.network`, `.redaction`, `.selector`). The supervisors, policy resolvers, DNS pipeline, relay managers, and the two Android `Service` classes live here. |

The package split expresses primary ownership, not a strict one-way dependency rule: a few lifecycle and telemetry collaborators cross the boundary in both directions. New cross-cutting infrastructure should land in an organized sub-package, not as another flat `services/*.kt` file.

Other `:core:service` packages — `hosts`, `keystore`, `security`, `storage`,
`strategy`, `utility` — are narrow support packages outside the scope of this
map.

## Android entry points

| Class | Extends | Role |
|-------|---------|------|
| `services.RipDpiVpnService` | `LifecycleVpnService` → `android.net.VpnService` | The VPN-mode foreground service. Hosts the VPN session lifecycle, the foreground notification, underlying-network binding, and `onRevoke`. |
| `services.RipDpiProxyService` | `LifecycleService` → `android.app.Service` | The proxy-mode foreground service. Hosts the proxy runtime coordinator. |
| `services.LifecycleVpnService` | `android.net.VpnService` | Lifecycle-aware `VpnService` base — bridges the AndroidX lifecycle dispatcher into the VPN service callbacks. |

Both services follow the foreground-service contract in
[`android-vpn-lifecycle.md`](../../.claude/rules/android-vpn-lifecycle.md):
`startForeground` within 5 s, visible notification, state persisted on every
transition. **Their lifecycle callbacks (`onCreate` / `onStartCommand` /
`onDestroy` / `onRevoke`) are behavior-frozen** — document, do not alter.

---

## Sub-area ownership map

### 1. Service lifecycle

The start/stop spine: the foreground services exist, transition states, and
shut down cleanly. Owners: `services.lifecycle.*`; the
`Service{Manager,LifecycleStateMachine,AutomationController,Clock,Status*}`
files; `RuntimeLifecycleRunner`; `PermissionWatchdog{,Coordinator}`,
`RuntimePermissionChecker`, `ScreenStateObserver`.

- **`ServiceLifecycleStateMachine`** enforces `STOPPED → STARTING → RUNNING →
  STOPPING` transitions.
- **`RuntimeLifecycleRunner`** drives runtime startup/shutdown with cleanup.
- **`DefaultServiceController`** (`core/service/src/main/kotlin/com/poyka/ripdpi/services/ServiceManager.kt`)
  issues the start/stop intents, with an optional
  `ServiceAutomationController` intercept.

### 2. Runtime / proxy / VPN orchestration

Composing the live runtime stack for each mode. Owners: `service.runtime.*`,
`service.session.*`; the flat `ServiceRuntime*`, `Vpn*Runtime*`,
`VpnTunnel*`, `ProxyRuntime*`, `VpnProtect*` / `ProtectSocket*`, and
`SharedProxyRuntimeStack` files.

- **`BaseServiceRuntimeCoordinator`** (`core/service/src/main/kotlin/com/poyka/ripdpi/services/ServiceRuntimeCoordinator.kt`)
  — the abstract per-mode orchestrator (runtime state, policy resolution,
  handover, permissions).
- **`VpnServiceRuntimeCoordinator`** / **`ProxyServiceRuntimeCoordinator`**
  (`core/service/src/main/kotlin/com/poyka/ripdpi/service/runtime/vpn/VpnServiceRuntimeCoordinator.kt`
  and
  `core/service/src/main/kotlin/com/poyka/ripdpi/service/runtime/proxy/ProxyServiceRuntimeCoordinator.kt`)
  — the concrete per-mode coordinators.
- **`VpnRuntimeCompositionCoordinator`** / **`ProxyRuntimeSupervisor`** —
  compose and supervise the tunnel / DNS / relay / protect stack.
- **`ServiceRuntimeRegistry`** tracks the live `ServiceRuntimeSession`.
- The `VpnProtect*` / `ProtectSocket*` files implement the
  `VpnService.protect()` fd-passing path — see
  [`vpnservice-protect-invariant.md`](../../.claude/rules/vpnservice-protect-invariant.md).

### 3. DNS & leak handling

The encrypted-DNS pipeline, leak detection, and ECH/bootstrap config refresh.
Owners: `services.dns.*` (incl. `.bootstrap`, `.failover`), `services.leak.*`,
`services.selector.*`; the flat `Dns*`, `VpnDns*`, `VpnResolver*`,
`VpnEncryptedDnsFailoverController`, `*ResolverCache` / `ResolverOverrideStore`,
`CaptivePortal*`, `Ipv6Leak*`, `CdnEch*`, `SharedPriors*` files.

- **`VpnDnsPolicyCoordinator`** coordinates DNS refresh planning + encrypted-DNS
  failover for a VPN session.
- **`DnsLeakDetector`** / **`Ipv6LeakDetector`** detect leaks; a confirmed leak
  drives `VpnEncryptedDnsFailoverController` onto the strict resolver.
- `services.dns.bootstrap` resolves the encrypted-DNS endpoints themselves.

### 4. Root helper

The opt-in privileged path for rooted devices. Owners: `RootHelperManager`,
`RootDetector`. Full contract:
[`ROOT_HELPER_CONTRACT.md`](../../docs/architecture/ROOT_HELPER_CONTRACT.md).
**Behavior-frozen** — the non-root baseline must hold.

### 5. Relay

Upstream relay selection, supervision, and the concrete relay families. Owners:
the `*RelayKindResolver` family + `RelayKindResolverRegistry`; the
`UpstreamRelay*` files; the `Subprocess*Relay*` family (subprocess transports);
`PluggableTransport*`; `NaiveProxy*`; the WARP family
(`Warp*` + `service.warp.*`); the Cloudflare family (`Cloudflare*`,
`*Masque*`, `GoogleAppsScriptRelayRuntime`).

- **`RelayKindResolverRegistry`** dispatches a `relay_kind` to its resolver —
  see [`CONFIG_CONTRACTS.md`](../../docs/architecture/CONFIG_CONTRACTS.md) §5
  for the frozen `relay_kind` strings.
- **`UpstreamRelaySupervisor`** supervises the active relay chain.
- WARP (Cloudflare WARP enrollment + tunnel) is a substantial relay family of
  its own; `service.warp.*` holds its bootstrap/config coordinators.

### 6. Policy memory

Per-network connection policy — resolution, signature, remembered-policy
matching, and direct-path learning. Owners: `ConnectionPolicy*`,
`ActiveConnectionPolicyStore`, `RememberedConnectionPolicyMatcher`,
`DirectPathPolicyLearner`, `LatestDirectModeOutcomeStore`,
`AntiCorrelationRoutingPolicy`, `RuntimeExperimentSelectionProvider`,
`NetworkFingerprintProvider`, `NetworkSnapshotFactory`.

- **`DefaultConnectionPolicyResolver`** (`core/service/src/main/kotlin/com/poyka/ripdpi/services/ConnectionPolicyResolver.kt`)
  is the policy-decision authority — it merges app settings, DNS, VPN, and the
  network-specific remembered policy.
- Persistence and the SHA-256 fingerprint key live in `:core:diagnostics-data`;
  see [`network-fingerprint-privacy.md`](../../.claude/rules/network-fingerprint-privacy.md).

### 7. Telemetry

Projecting native runtime telemetry into the service layer. Owners:
`service.telemetry.RuntimeTelemetryProjection`; the flat
`*TelemetryCoordinator`, `VpnTelemetry*`, `VpnRuntimeTelemetryReporter`,
`ServiceTelemetryLoopCoordinator`, `FieldTelemetry`, `ServiceLogContext` files.
Contract: [`TELEMETRY_CONTRACT.md`](../../docs/architecture/TELEMETRY_CONTRACT.md).

### 8. Network handover

Detecting and reacting to underlying-network changes (Wi-Fi ↔ cellular).
Owners: `services.network.*`; the flat `NetworkHandover{Monitor,Processor}`,
`ServiceRuntimeHandover{Coordinator,Restarter}`, `PolicyHandoverEventStore`,
`ConnectivityDegradationClassifier`, `EnvironmentDetector` files.

- **`NetworkHandoverMonitor`** emits a `NetworkHandoverEvent` on a qualifying
  switch; **`NetworkHandoverProcessor`** reacts with a retry-backed restart.

### 9. Diagnostics coordination

The service-side bridge to the diagnostics scan engine. Owner:
`DefaultDiagnosticsRuntimeCoordinator` — it sequences raw-path scans against
service state and the auto-resume setting. See
[`DIAGNOSTICS_ARCHITECTURE.md`](../../docs/architecture/DIAGNOSTICS_ARCHITECTURE.md).
Diagnostics-bundle scrubbing lives in `services.redaction.*`.

### 10. Owned-stack / browser support

The app-owned TLS/HTTP stack used for the `OWNED_STACK_ONLY` path. Owners:
`OwnedStackBrowser*`, `OwnedTlsClientFactory`, `OwnedStackEchEvidenceResolver`,
`HttpEngineOwnedStackPlatformBrowserExecutor`,
`BuildVersionOwnedStackBrowserSupportProvider`.

---

## Central coordinators

The orchestration spine — the classes most other files depend on. Each now
carries a class-level KDoc stating its role and place in this map:

`RipDpiVpnService`, `RipDpiProxyService`, `BaseServiceRuntimeCoordinator`,
`VpnServiceRuntimeCoordinator`, `ProxyServiceRuntimeCoordinator`,
`VpnRuntimeCompositionCoordinator`, `ProxyRuntimeSupervisor`,
`ServiceLifecycleStateMachine`, `DefaultConnectionPolicyResolver`,
`DefaultDiagnosticsRuntimeCoordinator`, `NetworkHandoverMonitor`.

## Extension notes

- A new relay kind: add a `*RelayKindResolver`, register it in
  `RelayKindResolverRegistry`, and follow
  [`FEATURE_EXTENSION_GUIDE.md`](../../docs/architecture/FEATURE_EXTENSION_GUIDE.md) §2.
- New cross-cutting infrastructure: prefer an organized `services.*`
  sub-package over another flat `services/*.kt` file.
- Anything touching the two `Service` classes' lifecycle callbacks, the
  foreground notification, or the root helper is behavior-frozen — see the
  constraints above.
