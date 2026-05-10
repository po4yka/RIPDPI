---
title: Bring Detection Feature to Full Parity with RKNHardering
type: epic
status: backlog
area: epic
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Epic — Bring Detection Feature to Full Parity with RKNHardering #repo/RIPDPI #area/epic #status/backlog ⏫

## Goal

Bring `core/detection` up to full functional parity with the detection pipeline in RKNHardering 2.7.1. Add 5 missing checkers, upgrade 5 existing ones, add IpConsensus synthesis, and harden the VerdictEngine rule matrix.

## Why now

RKNHardering is the reference implementation for VPN/bypass detection on Russian networks. RIPDPI's detection pipeline covers ~60% of the signal space RKNHardering covers. Missing checks (CDN-pulling TLS MITM detection, native hook detection, ICMP spoofing, RTT triangulation, multi-provider GeoIP consensus, cross-channel IP consensus) create blind spots that produce false `NOT_DETECTED` verdicts for sophisticated bypass configurations.

## Key decisions

- All new checkers follow the existing `*CheckerPort` / port-adapter pattern — no architectural changes to the pipeline skeleton.
- `IpConsensus` synthesis runs after all other checkers complete, same as in RKNHardering's `VpnCheckRunner`.
- `NativeSignsChecker` uses JNI via the existing Rust/NDK build layer; no new native module needed.
- `CallTransportLeakChecker` is added but disabled by default (same as RKNHardering), toggled via `DetectionSettings`.
- `CdnPullingChecker` and `RttTriangulationChecker` are also off by default.

## Scope

### Foundation

| Task | What | Priority |
|---|---|---|
| `add-detection-resolver-network-stack` | System/Direct/DoH DNS resolver + network binding + native curl fallback | high |
| `add-vpn-app-catalog-parity` | 27-family catalog, metadata scanner, localhostProxyPorts seeding | medium |

### New checkers (missing entirely)

| Task | Checker | Priority |
|---|---|---|
| `add-ip-comparison-checker` | IpComparisonChecker — 9 endpoints, RU vs non-RU groups | high |
| `add-cdn-pulling-checker` | CdnPullingChecker — CDN trace + TLS MITM detection | high |
| `add-native-signs-checker` | NativeSignsChecker — JNI getifaddrs, /proc/self/maps, root detection | high |
| `add-ip-consensus-synthesis` | IpConsensus — cross-channel IP aggregator | high |
| `add-icmp-spoofing-checker` | IcmpSpoofingChecker — ICMP probe to blocked vs control | medium |
| `add-rtt-triangulation-checker` | RttTriangulationChecker — RTT to RU vs foreign hosts | medium |
| `add-call-transport-leak-checker` | CallTransportLeakChecker — STUN sweeps, Telegram DC, WhatsApp | low |

### Upgrades to existing checkers

| Task | Checker | What changes |
|---|---|---|
| `upgrade-geo-ip-checker-multi-provider` | GeoIpChecker | 1 → 5 providers with majority-vote consensus |
| `upgrade-location-signals-checker-geolocation` | LocationSignalsChecker | Add BeaconDB cell-tower + Wi-Fi BSSID geolocation |
| `upgrade-indirect-signs-proc-net-socket-scan` | IndirectSignsChecker | Add /proc/net/tcp{6} listening socket scan for proxy ports |
| `upgrade-bypass-checker-mtproto-stun` | BypassChecker | Add MtProtoProber (Telegram DC2) + SOCKS5 UDP-associate for STUN |
| `upgrade-verdict-engine-rules-matrix` | VerdictEngine | Replace weighted scoring with 6-rule matrix + roaming relaxation |

### UI & presentation

| Task | What | Priority |
|---|---|---|
| `add-detection-settings-screen` | Full settings screen — feature toggles, DNS config, port range, debug mode | high |
| `add-detection-privacy-mode` | IP masking (maskIp / maskIpsInText) in UI and export | medium |
| `add-detection-export-share` | Markdown + JSON export formatters with share action | medium |
| `add-detection-verdict-narrative` | ExposureStatus, discovered rows, reason rows, home-routed roaming note | medium |
| `add-detection-color-vision-accessibility` | 4 CVD palettes, StatusVisualIndicator shape system, easter egg | medium |
| `add-detection-debug-mode` | DebugDiagnosticsFormatter, TunProbeDebugFormatter, copy-diagnostics action | low |

## Ship definition

- [ ] `DetectionResolverNetworkStack` with System/Direct/DoH modes used by all checkers
- [ ] `VpnAppCatalog` covers all 27 families; `ProxyScanner` popular-ports seeded from catalog
- [ ] All 7 new checkers implemented, wired via port-adapter, and covered by unit tests
- [ ] All 5 existing checker upgrades complete and regression-tested
- [ ] `VerdictEngine` updated to 6-rule matrix consuming `IpConsensus` result
- [ ] `DetectionCheckResult` and UI state models reflect all new evidence sources
- [ ] Detection Settings screen exposes all 32 preference keys
- [ ] Privacy mode masks IPs in UI and both export formats
- [ ] Markdown + JSON export with full `VerdictNarrative` and `IpConsensus` sections
- [ ] 4 CVD palettes implemented in `StatusVisualIndicator`
- [ ] Roborazzi goldens updated for all new and modified `DetectionCheckScreen` cards
- [ ] `CallTransportLeakChecker`, `CdnPullingChecker`, `RttTriangulationChecker` gated off by default

## Child tasks

### Foundation
- [[add-detection-resolver-network-stack]]
- [[add-vpn-app-catalog-parity]]

### New checkers
- [[add-ip-comparison-checker]]
- [[add-cdn-pulling-checker]]
- [[add-native-signs-checker]]
- [[add-ip-consensus-synthesis]]
- [[add-icmp-spoofing-checker]]
- [[add-rtt-triangulation-checker]]
- [[add-call-transport-leak-checker]]

### Checker upgrades
- [[upgrade-geo-ip-checker-multi-provider]]
- [[upgrade-location-signals-checker-geolocation]]
- [[upgrade-indirect-signs-proc-net-socket-scan]]
- [[upgrade-bypass-checker-mtproto-stun]]
- [[upgrade-verdict-engine-rules-matrix]]

### UI & presentation
- [[add-detection-settings-screen]]
- [[add-detection-privacy-mode]]
- [[add-detection-export-share]]
- [[add-detection-verdict-narrative]]
- [[add-detection-color-vision-accessibility]]
- [[add-detection-debug-mode]]

## Dependencies

- All new checkers depend on `add-detection-resolver-network-stack` (network client)
- `add-ip-consensus-synthesis` must follow all new checker ports being wired
- `upgrade-verdict-engine-rules-matrix` must follow `add-ip-consensus-synthesis`
- `add-detection-export-share` must follow `add-ip-consensus-synthesis` + `upgrade-verdict-engine-rules-matrix`
- `add-detection-verdict-narrative` must follow `upgrade-verdict-engine-rules-matrix`
- `add-detection-privacy-mode` blocks `add-detection-export-share`
- `add-native-signs-checker` requires NDK CMakeLists.txt additions
- `add-vpn-app-catalog-parity` blocks `ProxyScanner` popular-ports seeding (in `upgrade-bypass-checker-mtproto-stun`)

## TDD policy

All child tasks under this epic follow TDD. The invariant rule: **test files must exist and fail before implementation begins.**

1. **Write tests first** — create the test file(s) with assertions for the specified behaviour. The file must compile (stub the types if needed) but assertions must fail.
2. **Confirm red** — run the relevant Gradle command and verify the expected failures. Screenshot goldens: run `recordRoborazzi` and confirm no golden exists yet.
3. **Implement** — write the minimum production code to make failing tests pass. No speculative code beyond what tests require.
4. **Confirm green** — re-run the full test command. Zero regressions on existing tests.
5. **Refactor** — clean up while keeping tests green.

**Test commands by module:**

| Scope | Command |
|---|---|
| `core/detection` unit tests | `./gradlew :core:detection:test` |
| `core/diagnostics-data` unit tests | `./gradlew :core:diagnostics-data:test` |
| `app` unit tests | `./gradlew :app:test` |
| Record Roborazzi goldens | `./gradlew :app:recordRoborazziDebug` |
| Verify Roborazzi goldens | `./gradlew :app:verifyRoborazziDebug` |
| Full check | `./gradlew :core:detection:test :core:diagnostics-data:test :app:test :app:verifyRoborazziDebug` |

No task may be marked done unless its CI test command passes with zero failures.

## Risks / open questions

- `getifaddrs()` JNI on Android: tested on API 26–36 in RKNHardering; verify ABI matrix (arm64-v8a, armeabi-v7a, x86_64) in RIPDPI's NDK build
- BeaconDB external API dependency: requires network permission already present; rate limits unknown
- Root/hook detection may produce false positives on Magisk-less devices with custom ROMs — needs confidence tuning
- ICMP probing shells out to `ping`; verify it works across all target API levels without root
