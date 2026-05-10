---
title: Expand VpnAppCatalog to Full RKNHardering Coverage with Metadata Scanner
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: detection-feature-parity-epic
blocks: []
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Expand VpnAppCatalog to Full RKNHardering Coverage with Metadata Scanner #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Expand `VpnAppCatalog` to cover all 33 entries / 27 families from RKNHardering, add `VpnAppMetadataScanner` for APK inspection (appType, coreType, corePath, goVersion), and wire `localhostProxyPorts` / `familiesForPort()` helpers used by `ProxyScanner`.

## Context

RIPDPI's `VpnAppCatalog` exists but its coverage is unknown. RKNHardering's catalog covers 27 bypass app families including Xray-API-capable apps with their known proxy ports — this list is the seed for `ProxyScanner`'s popular-ports mode. Missing catalog entries mean RIPDPI's port scanner won't probe the right ports in popular-only mode.

**27 families in RKNHardering catalog:**
Xray/V2Ray, sing-box, NekoBox, HAPP, Karing, avoVPN, Hiddify, MikuBox, AeroBox, CatBox, FireflyVPN, Husi, v2RayTun, v2box, Exclave, Clash, Shadowsocks, Tor/Orbot, Outline, WireGuard, IPSec/L2TP, Psiphon, Lantern, DPI bypass (zapret/byedpi), AmneziaVPN, tg-ws-proxy, Termux

**Xray-API-capable apps:** v2rayNG, Xray (saeeddev94), v2RayTun, v2box, HAPP — ports 1080/8080/10808/10809

**Reference files:**
- `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/VpnAppCatalog.kt`
- `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/VpnAppMetadataScanner.kt`
- `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/InstalledVpnAppDetector.kt` — 3 strategies

**RIPDPI files to audit and update:**
- `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/vpn/VpnAppCatalog.kt`
- `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/vpn/InstalledVpnAppDetector.kt`
- Add: `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/vpn/VpnAppMetadataScanner.kt`

**InstalledVpnAppDetector — 3 detection strategies:**
1. Catalog match — TARGETED_BYPASS→MEDIUM confidence, GENERIC_VPN→LOW
2. VPN service declaration — `queryIntentServices(VpnService.SERVICE_INTERFACE)` → MEDIUM confidence
3. Name heuristic — all non-system apps with "VPN" in display name → LOW confidence, `detected=false`

## Acceptance criteria

- [ ] All 27 families present in `VpnAppCatalog` with package names, signal type, default proxy ports
- [ ] `localhostProxyPorts: List<Int>` computed as deduplicated sorted union of all `defaultPorts` across all signatures
- [ ] `familiesForPort(port: Int): Set<String>` returns family names whose defaultPorts include that port
- [ ] `ProxyScanner` uses `VpnAppCatalog.localhostProxyPorts + listOf(1081, 7890, 7891)` as its popular-ports list
- [ ] `VpnAppMetadataScanner.scan(packageInfo)` reads APK, extracts: appType, coreType, corePath, goVersion, versionName, services list
- [ ] `InstalledVpnAppDetector` uses all 3 strategies; results de-duplicated by package name; system apps marked
- [ ] Finding descriptions include `VpnAppMetadataScanner.formatMetadataSuffix()` appended
- [ ] Unit tests: assert `localhostProxyPorts` is non-empty and sorted; assert `familiesForPort(1080)` returns expected families

## TDD workflow

1. **Write tests first** — create before any implementation:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/vpn/VpnAppCatalogTest.kt`:
     - `localhost_proxy_ports_is_sorted_and_contains_1080()` — fails until catalog expanded
     - `localhost_proxy_ports_contains_xray_ports()` — assert 10808 and 10809 present
     - `families_for_port_1080_returns_known_families()` — assert result contains at least `Xray/V2Ray` and `Shadowsocks`
     - `families_for_port_9999_returns_empty()` — assert empty set for unknown port
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/vpn/InstalledVpnAppDetectorTest.kt`:
     - `catalog_match_strategy_returns_targeted_bypass_confidence()` — inject a fake PackageInfo matching a catalog entry; assert confidence MEDIUM
     - `name_heuristic_strategy_detected_false()` — inject app with "VPN" in name not in catalog; assert `detected=false`
2. **Confirm red** — `./gradlew :core:detection:test` — all tests fail
3. **Implement** — expand catalog, add `localhostProxyPorts`, `familiesForPort()`, `VpnAppMetadataScanner`, update `InstalledVpnAppDetector`
4. **Confirm green** — `./gradlew :core:detection:test`
5. **Refactor** — deduplicate port list construction

## Definition of done

Unit tests green. Detection run identifies v2rayNG, Hiddify, and AmneziaVPN as expected families. Port scanner popular-ports list matches RKNHardering's list.
