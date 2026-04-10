# Integrations Roadmap - Phase P0 Implementation

This file translates the highest-priority roadmap work into repo-specific implementation tracks. The research source of truth remains [roadmap-integrations.md](roadmap-integrations.md).

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

## Recommended Sequence

1. Finish WARP as a production-grade upstream. Lock down registration, refresh, endpoint persistence, and service restart recovery around `WarpProvisioningClient`, `WarpEnrollmentOrchestrator`, `WarpStores`, and `WarpRuntimeSupervisor`, and treat the control-plane hostlist as mandatory bootstrap coverage for desync.

2. Close the WARP operator-hardening gap. Turn the existing AmneziaWG settings into validated presets, wire the endpoint scanner into per-network memory and manual override flows, and make diagnostics distinguish provisioning failures, endpoint reachability failures, and runtime tunnel failures.

3. Add xHTTP as the first new relay transport in this phase. Implement it as a transport extension around the existing VLESS relay stack, with a dedicated native crate and integration through `ripdpi-relay-core`, `RelaySettings.kt`, the engine JSON codecs, `UpstreamRelaySupervisor`, and the relay config UI.

4. Standardize Chrome fingerprints across all client-originated TLS. Make `ripdpi-tls-profiles` the only supported constructor for outbound TLS where BoringSSL can be used, add regression tests that capture ClientHello bytes, and eliminate silent fallbacks to library-default fingerprints in WARP bootstrap and relay transports.

5. Add Cloudflare Tunnel only after the TLS and xHTTP substrate is stable. Integrate it through the existing relay model instead of adding a one-off service path, and prefer the lowest-risk client architecture that gets a working transport into the app quickly.

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
