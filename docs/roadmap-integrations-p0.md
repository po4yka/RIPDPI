# Integrations Roadmap - Phase P0 Implementation

This file translates the highest-priority roadmap work into repo-specific implementation tracks. The research source of truth remains [roadmap-integrations.md](roadmap-integrations.md).

## Current Closure Status

Phase P0 is implemented in-repo.

- WARP provisioning, runtime supervision, enrollment orchestration, endpoint persistence, and native execution are present across `core/data`, `core/service`, `core/engine`, `native/rust/crates/ripdpi-warp-core`, and `native/rust/crates/ripdpi-warp-android`.
- The Cloudflare WARP control-plane host set is shipped as built-in data in `core/data/src/main/kotlin/com/poyka/ripdpi/data/WarpSettings.kt`.
- xHTTP is implemented as a first-class relay transport in `native/rust/crates/ripdpi-xhttp` and is wired through the relay runtime, config model, and editor UI.
- Chrome-profile TLS fingerprint handling is present in `native/rust/crates/ripdpi-tls-profiles` and is reused across the transport stack.
- `cloudflare_tunnel` ships as a supported relay kind backed by the xHTTP client path, with explicit UI guidance that the user supplies the hostname.

## Scope

- [Item 1 - WARP WireGuard tunnel](roadmap-integrations.md#1-warp-wireguard-tunnel)
- [Item 2 - AmneziaWG obfuscation layer](roadmap-integrations.md#2-amneziawg-obfuscation-layer)
- [Item 3 - WARP domain hostlist for DPI desync](roadmap-integrations.md#3-warp-domain-hostlist-for-dpi-desync)
- [Item 25 - xHTTP transport for VLESS](roadmap-integrations.md#25-xhttp-transport-for-vless)
- [Item 26 - JA3/JA4 Chrome fingerprint for all TLS](roadmap-integrations.md#26-ja3ja4-chrome-fingerprint-for-all-client-originated-tls)
- [Item 32 - Cloudflare Tunnel transport](roadmap-integrations.md#32-cloudflare-tunnel-cloudflared-as-relay-transport)

## Current Repo Footing

- WARP already has settings, enrollment, runtime supervision, and native runtime layers in `core/data`, `core/service`, `core/engine`, `native/rust/crates/ripdpi-warp-core`, and `native/rust/crates/ripdpi-warp-android`.
- The WARP control-plane hostlist from item 3 is already represented in `core/data/src/main/kotlin/com/poyka/ripdpi/data/WarpSettings.kt` as `BuiltInWarpControlPlaneHosts`.
- AmneziaWG packet obfuscation is already implemented inside `native/rust/crates/ripdpi-warp-core/src/lib.rs`; this phase is about validation, presets, and rollout hardening rather than inventing the codec.
- Relay orchestration already exists for VLESS, Hysteria2, MASQUE, and chain relay through `core/data/src/main/kotlin/com/poyka/ripdpi/data/RelaySettings.kt`, `core/service/src/main/kotlin/com/poyka/ripdpi/services/UpstreamRelaySupervisor.kt`, `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiRelay.kt`, and `native/rust/crates/ripdpi-relay-core`.
- Chrome-like TLS profile support already exists in `native/rust/crates/ripdpi-tls-profiles`, and one production caller already uses it in `native/rust/crates/ripdpi-ws-tunnel/src/connect.rs`. The remaining gap is uniform adoption across every outbound TLS and QUIC origin.

## Remaining Follow-Up Scope

No open P0 roadmap item remains as an unimplemented feature. The remaining work is follow-up hardening and rollout support:

- Cloudflare Tunnel onboarding is still intentionally user-managed. RIPDPI accepts a user-managed hostname and xHTTP origin, but it does not provision tunnel tokens, create TryCloudflare tunnels, or operate `cloudflared` on the user’s behalf.
- Cloudflare-specific MASQUE and Cloudflare-direct rollout hardening remain separate follow-up work under [relay-masque-status.md](native/relay-masque-status.md), not unfinished P0 delivery.
- TLS profile freshness, strategy-pack delivery, and long-tail fingerprint rotation now belong to P3 hardening rather than unfinished P0 transport work.

## P0 Cloudflare Tunnel Topology

- P0 ships `cloudflare_tunnel` as an xHTTP-backed relay specialization, not as an embedded `cloudflared` client.
- The relay `server` and `serverName` fields must point at a user-managed Cloudflare Tunnel hostname.
- That hostname is expected to front a user-managed xHTTP/VLESS origin.
- RIPDPI does not provision tunnel tokens, launch `cloudflared`, or offer TryCloudflare onboarding in Phase 0.
- UDP is always off for this relay kind, and Chrome TLS fingerprinting is mandatory.

## Repo Touchpoints

- Data and persistence: `core/data/src/main/kotlin/com/poyka/ripdpi/data/WarpSettings.kt`, `core/data/src/main/kotlin/com/poyka/ripdpi/data/WarpStores.kt`, `core/data/src/main/kotlin/com/poyka/ripdpi/data/RelaySettings.kt`, `core/data/src/main/kotlin/com/poyka/ripdpi/data/AppSettingsJson.kt`
- Service orchestration: `core/service/src/main/kotlin/com/poyka/ripdpi/services/WarpProvisioningClient.kt`, `core/service/src/main/kotlin/com/poyka/ripdpi/services/WarpEnrollmentOrchestrator.kt`, `core/service/src/main/kotlin/com/poyka/ripdpi/services/WarpRuntimeSupervisor.kt`, `core/service/src/main/kotlin/com/poyka/ripdpi/services/UpstreamRelaySupervisor.kt`
- Engine bridge: `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiWarp.kt`, `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiRelay.kt`, `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiProxyUIPreferences.kt`
- Native runtime: `native/rust/crates/ripdpi-warp-core`, `native/rust/crates/ripdpi-warp-android`, `native/rust/crates/ripdpi-relay-core`, `native/rust/crates/ripdpi-tls-profiles`
- UI: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/WarpSection.kt`, `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/config/RelayFields.kt`, `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/config/ModeEditorScreen.kt`, `app/src/main/kotlin/com/poyka/ripdpi/activities/SettingsViewModel.kt`, `app/src/main/kotlin/com/poyka/ripdpi/activities/ConfigViewModel.kt`

## Exit Criteria

- WARP can be provisioned, refreshed, scanned, and started without manual settings edits.
- AmneziaWG on WARP has presets and validation rather than raw expert-only tuning.
- xHTTP is available as a relay transport with config round-trip coverage and runtime startup tests.
- Every outbound TLS path that RIPDPI owns uses an explicit fingerprint policy.
- Cloudflare Tunnel has a documented and code-level insertion point in the relay stack, even if advanced onboarding remains follow-up work.

All P0 exit criteria above are now satisfied in-repo.
