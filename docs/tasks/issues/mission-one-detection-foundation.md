---
title: "Mission One: Detection Foundation — Resolver Stack, Multi-Provider GeoIP, Catalog"
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: detection-feature-parity-epic
blocks: [add-ip-comparison-checker, add-cdn-pulling-checker, add-icmp-spoofing-checker, add-rtt-triangulation-checker, upgrade-bypass-checker-mtproto-stun]
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Mission One — Detection Foundation: Resolver Stack, Multi-Provider GeoIP, Catalog #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Objective

Deliver the three foundational pieces that unblock all subsequent detection parity work — with no JNI, no UI changes, and no new dependencies — so every checker added afterward has a correct network client, a seeded port list, and a reliable GeoIP signal.

## What this mission delivers

| Task | Outcome |
|---|---|
| `add-detection-resolver-network-stack` | Configurable DNS (System/Direct/DoH), network binding, native curl fallback — the HTTP client layer used by all new and upgraded checkers |
| `upgrade-geo-ip-checker-multi-provider` | GeoIpChecker queries 5 providers with majority-vote consensus instead of 1; immediate improvement in proxy/hosting classification accuracy |
| `add-vpn-app-catalog-parity` | VpnAppCatalog expanded to 27 families + VpnAppMetadataScanner; ProxyScanner popular-ports list seeded from catalog |

## Why these three first

- **No blockers** — none of the three tasks have upstream dependencies; work can start immediately and run in parallel across three branches.
- **Pure Kotlin** — no NDK, no Compose changes, no schema migrations needed.
- **Unblocks the rest** — `add-detection-resolver-network-stack` is listed as a blocker for 4 high-priority checker tasks. Without it, IpComparison, CdnPulling, ICMP, and RTT checks all share a bare OkHttp client that can't bind to specific networks or use DoH. `add-vpn-app-catalog-parity` seeds the popular-ports list used by ProxyScanner.
- **Verifiable in isolation** — each task has unit tests that can confirm correctness before wiring into the full pipeline.

## Execution order

Tasks can proceed in parallel on separate branches. Suggested split:

**Branch A** — `add-detection-resolver-network-stack`
- Implement `DetectionResolverNetworkStack`, `DirectDns`, `FilteringDns`, `CancellableDns`
- Wire `NativeCurlBridge` with CA bundle; add `CombinedTransportIOException`
- Unit tests: mock UDP DNS server, assert DIRECT query format; assert config caching

**Branch B** — `upgrade-geo-ip-checker-multi-provider` *(can start after resolver stack draft is reviewable)*
- Extend `GeoIpChecker` to query `ipapi.is`, `iplocate.io`, `ipquery.io`, `iplookup.it`, `ipbot.com`
- Implement seed-IP then re-query pattern; add majority-vote merge
- Extend `GeoIpFacts` with `providerCount` and `conflictingProviders`
- Unit tests: 3-2 majority splits; graceful 2-provider failure

**Branch C** — `add-vpn-app-catalog-parity` *(fully independent)*
- Audit current `VpnAppCatalog` against RKNHardering's 27 families
- Add missing families; add `localhostProxyPorts` and `familiesForPort()` helpers
- Add `VpnAppMetadataScanner` APK inspector
- Update `InstalledVpnAppDetector` to use all 3 detection strategies
- Unit tests: `localhostProxyPorts` non-empty, sorted; `familiesForPort(1080)` correct

## TDD workflow

Each branch follows strict TDD. Write the test file first, confirm it fails, then implement.

**Branch A — `add-detection-resolver-network-stack`**
1. Create `core/diagnostics-data/src/test/kotlin/com/poyka/ripdpi/data/diagnostics/DetectionResolverNetworkStackTest.kt` and `DirectDnsTest.kt` — assert DIRECT mode builds correct UDP wire format; assert `FilteringDns` drops wrong address family; assert config caching (client object identity stable when config unchanged); all fail until implementation exists
2. Run `./gradlew :core:diagnostics-data:test` — confirm failures
3. Implement `DetectionResolverNetworkStack`, `DirectDns`, `FilteringDns`, `CancellableDns`
4. Re-run — green; zero regressions

**Branch B — `upgrade-geo-ip-checker-multi-provider`**
1. Create `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/checker/GeoIpCheckerTest.kt` — assert 3-2 proxy-flag majority returns `true`; assert 2 providers failing still returns a result; assert `providerCount` field equals number of successful responses; all fail until 5-provider logic exists
2. Run `./gradlew :core:detection:test` — confirm failures
3. Extend `GeoIpChecker`
4. Re-run — green

**Branch C — `add-vpn-app-catalog-parity`**
1. Create `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/vpn/VpnAppCatalogTest.kt` — assert `localhostProxyPorts` is non-empty, sorted, and contains 1080; assert `familiesForPort(1080)` returns a non-empty set; assert `familiesForPort(9999)` returns empty; all fail until catalog is expanded
2. Run `./gradlew :core:detection:test` — confirm failures
3. Expand catalog + add helpers
4. Re-run — green

## Completion gate

Mission One is done when:

- [ ] `DetectionResolverNetworkStack` merged; all existing detection HTTP calls migrated to use it
- [ ] `GeoIpChecker` queries 5 providers; unit tests green
- [ ] `VpnAppCatalog` has 27 families; `ProxyScanner` uses `localhostProxyPorts` as popular list
- [ ] All three sets of unit tests pass in CI
- [ ] No regressions on existing `DetectionCheckScreen` Roborazzi goldens

## What Mission One does NOT include

- No new checker screens or UI cards (that comes after IpConsensus + VerdictEngine work)
- No NDK/JNI changes
- No settings screen changes
- No export formatters

## Reference files

- Resolver: `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/ResolverNetworkStack.kt`
- DirectDns: `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/DirectDns.kt`
- NativeCurlBridge: `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/NativeCurlBridge.kt`
- GeoIpChecker: `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/GeoIpChecker.kt`
- VpnAppCatalog: `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/VpnAppCatalog.kt`
- VpnAppMetadataScanner: `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/VpnAppMetadataScanner.kt`
- InstalledVpnAppDetector: `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/checker/InstalledVpnAppDetector.kt`

## RIPDPI extension points

- New file: `core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DetectionResolverNetworkStack.kt`
- Modify: `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/GeoIpChecker.kt`
- Modify: `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/vpn/VpnAppCatalog.kt`
- Modify: `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/vpn/InstalledVpnAppDetector.kt`
- New file: `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/vpn/VpnAppMetadataScanner.kt`
- Modify: `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/probe/ProxyScanner.kt` — replace hardcoded popular ports with `VpnAppCatalog.localhostProxyPorts`
