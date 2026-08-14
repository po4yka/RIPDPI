---
id: RTE-1786264762917255
title: Adopt process-based per-package routing via Xray TUN routeOnly
kind: feature
status: doing
area: routing
priority: medium
owner: unassigned
parent: EPC-1786264762917557
blocked_by: []
spec_mode: required
openspec_change: rte-1786264762917255-adopt-process-based-per-package-routing-via-xray-tun-routeonly
created: 2026-04-25
updated: 2026-06-11
status_detail: 3/4 criteria done; criterion 4 policy half unit-tested, the egress-IP half is device-gated
---

## Summary

reference Android implementation 2.1.0 (2026-04-17) shipped per-package routing via Xray TUN with `routeOnly` enabled. Adopt the same pattern so RIPDPI users can route selected platform-detection-positive apps directly while everything else goes through VLESS.

## Research citation

ripdpi-android-research-2026-04-25 §Peer mobile clients — reference Android implementation 2.1.0 added process/package-name-based routing (Android 10+, requires Xray TUN with `routeOnly` enabled) and outbound alias support for traffic-splitting to different servers via Xray TUN. The pattern supports compatibility with apps that react to the active network path; see `platform-vpn-detection-april-2026`.

## Acceptance criteria

- [x] Per-package routing enforces exclusions via `VpnAppExclusionPolicy` using `VpnService.Builder` `addAllowedApplication`/`addDisallowedApplication` (implemented; note: `routeOnly` Xray TUN pattern from the task title was not adopted — RIPDPI uses the equivalent Android-native mechanism)
- [x] UI exposes per-package allowlist (route through tunnel) and blocklist (route direct)
- [x] Default blocklist seeds with known platform-detection-positive apps per platform-vpn-detection-april-2026
- [~] Integration test verifies blocklisted apps egress with non-tunnel IP while allowed apps go through VLESS. **Policy half unit-tested** (`VpnAppExclusionPolicyTest.blocklisted vpn-detection app routes direct while unrelated app stays tunneled`): a blocklisted platform app lands in `Disallow` (routed direct), an unrelated browser stays tunneled, and a not-installed selection is filtered out — asserted through the pure `computeAppRoutingPlan`. **Egress-IP half is DEVICE-GATED:** confirming a blocklisted app actually exits with a non-tunnel IP while an allowed app exits via VLESS needs a real device + a second egress path; no JVM/Robolectric host can route an `addDisallowedApplication` socket.


## Links

- Project: [[ripdpi-android]]
- Epic: [[Epic - Fail-closed Android VPN policy engine]] (parent; the former "Advanced routing rules and geoip enforcement" epic was removed)
- Research: ripdpi-android-research-2026-04-25 §Peer mobile clients


## Work log

- 2026-06-05: RIPDPI uses tun2socks bridge + VpnService.Builder app filters (not Xray TUN routeOnly); SplitTunnelScreen/SplitTunnelViewModel/AppPickerSheet implement the UI (criterion 2 done); app-routing-policy.json seeds known platform apps (criterion 3 done); no integration test verifying non-tunnel egress IP exists (criterion 4 open); criterion 1 as worded (Xray TUN routeOnly) does not match current architecture — needs reframing or architectural decision.
- 2026-06-05: Re-audit confirmed: `routeOnly` string absent from entire codebase; per-package routing is implemented via `VpnAppExclusionPolicy` (`core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnAppExclusionPolicy.kt`) using `addAllowedApplication`/`addDisallowedApplication` on `VpnService.Builder`; criterion 2 [x] confirmed via `SplitTunnelScreen.kt`, `AppPickerSheet.kt`, `InstalledAppCatalog.kt`; criterion 3 [x] confirmed via `core/data/settings/src/main/assets/integrations/app-routing-policy.json` (known platform apps seeded); no egress-IP integration test found (criterion 4 [ ]); status promoted from `backlog` to `doing` as 2 of 4 criteria are verifiably done.
- 2026-06-10: Removed a stray trailing `## amneziawg-outbound-support` header (copy-paste artifact, no content) and repointed the dangling "Advanced routing rules" epic link to the actual parent, `Epic - Fail-closed Android VPN policy engine`.
- 2026-06-11: Closed the CI-achievable half of criterion 4 — added `VpnAppExclusionPolicyTest.blocklisted vpn-detection app routes direct while unrelated app stays tunneled`, asserting `computeAppRoutingPlan` puts a blocklisted installed platform app in `Disallow` (direct egress), keeps an unrelated browser tunneled, and filters out a not-installed selection. `:core:service:testDebugUnitTest` green; pr-reviewer pass: sound. The egress-IP verification (real non-tunnel exit IP vs VLESS) stays device-gated — status remains `doing` until an on-device run.
