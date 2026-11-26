# ADR 0016: Server journey code decisions

> Status: Accepted. Decision date: 2026-11-25.

## Context

These decisions moved out of source comments added by the server health work: the saved AmneziaWG server check, the connected server on the status screen, server names in relay events, and the AmneziaWG profile editor. Paths are relative to the repository root.

## Decisions

### core/service/src/main/kotlin/com/poyka/ripdpi/services/AwgEndpointCheck.kt#AwgEndpointCheck

While a VPN session owns the device, the probe socket must stay under `VpnService.protect`. The protect lease keeps the socket outside the tunnel for the full length of the check.

The native runtime sends its codec in passthrough mode when every obfuscation parameter has its default value. The probe request must use the same rule. If it does not, the probe handshake has a different wire shape from the real session.

The check timeout is 5 seconds. This is long enough for a distant server. It is short because a running check delays the start of a VPN session (see `ActiveProtectSocketPathProvider.set`).

### core/service/src/test/kotlin/com/poyka/ripdpi/services/AwgEndpointCheckTest.kt#AwgEndpointCheckTest

A profile without AmneziaWG 3.x keys keeps the WARP wire shape. The test pins this so the probe does not send AmneziaWG-only fields to such a server.

### core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/RipDpiProxyUIConfig.kt#RipDpiWarpAmneziaConfig

Only an AmneziaWG endpoint check sets the new field. When it is not set, the WARP config JSON stays unchanged.

### app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/state/ServerNames.kt#ServerNames

Relay health events carry `opaqueRelayProfileToken(memberId)`. The saved selector members give the names. Names exist on this device only. They never enter the event store or an export.

An unreadable catalog leaves the events without names.

### core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/DiagnosticsBoundaryModels.kt#DiagnosticEvent

The relay profile token is transient. It is the `sha256(profileId)` of the relay that the event concerns. Exports carry only the archive alias.

### app/src/main/kotlin/com/poyka/ripdpi/ui/screens/awg/AmneziaWgProfileViewModel.kt#AmneziaWgProfileViewModel

A config exception can contain secrets. The view model exposes only the failed status and never the exception text.

An edit made while a write runs is not in the stored row. The view model must not report such a state as saved. It compares the editor and the name with the values at the time of the tap.

The default profile name comes from the cohort id. The cohort id is server-coordinated and not private to the user. The endpoint host is not used, so the host does not appear in a profile list label unless the user types it.

### app/src/main/kotlin/com/poyka/ripdpi/ui/screens/awg/AmneziaWgProfileScreen.kt#AmneziaWgProfileScreen

The generated public key is not a secret. The copy action uses the plain clipboard and not the sensitive clip.

### native/rust/crates/ripdpi-android-telemetry-adapter/src/observer.rs#ProxyTelemetryObserver

A DNS sinkhole answers with the unspecified address, and the kernel dials it as loopback. A loopback target refuses for every group. Its advance is not evidence about the route, so it does not count as a route change.

The route change counter is read for display only. It needs no happens-before edge, so `Ordering::Relaxed` is correct.

### native/rust/crates/ripdpi-android-telemetry-adapter/src/tests.rs#tests

A DNS sinkhole answers 0.0.0.0, which the kernel dials as 127.0.0.1. The refused dial advances the route at start-up. The test covers this case.

### native/rust/crates/ripdpi-warp-core/src/amneziawg_runtime.rs#AmneziaWgRuntime

The tunnel is published for telemetry only after the last early return in startup. A failed start must not leave a tunnel visible to readers.

### native/rust/crates/ripdpi-warp-core/src/wireguard/tunnel.rs#WireGuardTunnel

The handshake round trip is a telemetry read. It orders nothing else, so `Ordering::Relaxed` is enough for the load. The writer stores the value before it signals readiness, so a reader woken by readiness sees the round trip.

The boringtun library reports whole milliseconds. The writer clamps the value to at least 1 ms, so a sub-millisecond answer still reads as measured and `0` keeps the meaning of "no response yet". The test waits 40 ms before the answer to show that the stored value is the measured round trip and not the clamp.

### core/service/src/main/kotlin/com/poyka/ripdpi/services/TunnelKeyPairGenerator.kt#TunnelKeyPairGenerator

The interface lets `:app` generate WireGuard-family interface keys without a dependency on `:core:engine` types. `DefaultTunnelKeyPairGenerator` adapts the engine generator behind it.

### app/src/main/kotlin/com/poyka/ripdpi/ui/screens/proxyimport/SubscriptionImportConfirmViewModel.kt#SubscriptionImportConfirmViewModel

After a bundle file import, the view model finds the saved group with `localBundleTarget`, the same rule the import used to pick it, so it needs no group ID in `SubscriptionRefreshResult.Updated`. The result lists the group's members, which are saved as relay profiles under the member ID. Select and Connect call `ImportedServerSelection`, the selector that the server list uses, through `selectReturningStartNeeded`, which the single-link confirm screen also uses. AmneziaWG profiles from the bundle are not listed: the server list has no Select for them, and a profile without its key still opens the key screen when the result closes.
