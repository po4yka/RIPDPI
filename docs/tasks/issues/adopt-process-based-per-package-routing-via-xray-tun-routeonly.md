---
title: Adopt process-based per-package routing via Xray TUN routeOnly
type: task
status: doing
area: routing
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-25
updated: 2026-06-05
---

## Summary

reference Android implementation 2.1.0 (2026-04-17) shipped per-package routing via Xray TUN with `routeOnly` enabled. Adopt the same pattern so RIPDPI users can route VPN-detection-positive Russian apps (Sber, RuStore, Wildberries, T-Bank, etc.) directly while everything else goes through VLESS — addressing the platform-VPN-detection regime active since 2026-04-15.

## Research citation

ripdpi-android-research-2026-04-25 §Peer mobile clients — reference Android implementation 2.1.0 added process/package-name-based routing (Android 10+, requires Xray TUN with `routeOnly` enabled) and outbound alias support for traffic-splitting to different servers via Xray TUN. The pattern complements the platform-VPN-detection regime that began enforcement on 2026-04-15 (RKS Global: 22/30 top Russian apps detect VPN, 19/30 report VPN status server-side; see `platform-vpn-detection-april-2026`).

## Acceptance criteria

- [ ] TUN bridge enables `routeOnly` mode per reference Android implementation 2.1.0 reference
- [x] UI exposes per-package allowlist (route through tunnel) and blocklist (route direct)
- [x] Default blocklist seeds with VPN-detection-positive apps (RuStore, Sber, Wildberries) per platform-vpn-detection-april-2026
- [ ] Integration test verifies blocklisted apps egress with non-tunnel IP while allowed apps go through VLESS


## Links

- Project: [[ripdpi-android]]
- Epic: [[Epic - Advanced routing rules and geoip enforcement]]
- Research: ripdpi-android-research-2026-04-25 §Peer mobile clients


## Work log

- 2026-06-05: RIPDPI uses tun2socks bridge + VpnService.Builder app filters (not Xray TUN routeOnly); SplitTunnelScreen/SplitTunnelViewModel/AppPickerSheet implement the UI (criterion 2 done); app-routing-policy.json seeds Sber/Wildberries/RuStore/VK-Store (criterion 3 done); no integration test verifying non-tunnel egress IP exists (criterion 4 open); criterion 1 as worded (Xray TUN routeOnly) does not match current architecture — needs reframing or architectural decision.
- 2026-06-05: Re-audit confirmed: `routeOnly` string absent from entire codebase; per-package routing is implemented via `VpnAppExclusionPolicy` (`core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnAppExclusionPolicy.kt`) using `addAllowedApplication`/`addDisallowedApplication` on `VpnService.Builder`; criterion 2 [x] confirmed via `SplitTunnelScreen.kt`, `AppPickerSheet.kt`, `InstalledAppCatalog.kt`; criterion 3 [x] confirmed via `core/data/settings/src/main/assets/integrations/app-routing-policy.json` (Sber, Wildberries, RuStore, VK seeded); no egress-IP integration test found (criterion 4 [ ]); status promoted from `backlog` to `doing` as 2 of 4 criteria are verifiably done.

## amneziawg-outbound-support
