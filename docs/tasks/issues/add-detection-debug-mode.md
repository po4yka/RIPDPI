---
title: Add Detection Debug Mode with Full Diagnostics Formatter
type: task
status: backlog
area: diagnostics
priority: low
owner: unassigned
parent: detection-feature-parity-epic
blocks: []
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Detection Debug Mode with Full Diagnostics Formatter #repo/RIPDPI #area/diagnostics #status/backlog 🔽

## Objective

Add a debug mode to the detection feature that produces a verbose `DebugDiagnosticsFormatter` output — including per-step timing, raw interface dumps, native maps markers, and TUN probe detail — accessible via a "Copy diagnostics" action in `DetectionCheckScreen`.

## Context

RKNHardering's debug mode outputs a detailed bracket-sectioned text dump invaluable for support and root-cause analysis. RIPDPI has `crash/CrashReportWriter` but no equivalent detection-level debug formatter. This task adds parity with `DebugDiagnosticsFormatter` and `TunProbeDiagnosticsFormatter`.

**Debug output sections (from RKNHardering):**
- All `CheckSettings` / `DetectionSettings` fields
- Resolver: mode, preset, servers, DoH URL, bootstrap (IPs masked per privacy mode)
- Per-category: name, detected, needsReview, hasError, findings, evidence, matchedApps, activeApps, callTransport
- `[indirectSigns.performance]`: totalDurationMs, dominantDelay, per-step timing; callTransport timing
- `[locationSignals.debug]`: permission states, cell info counts/types/candidates, BeaconDB usage, Wi-Fi scan candidates (cached/fresh/connected), BSSID source/unavailableReason
- `[nativeSigns.raw]`: ifconfig dump (IPs masked), getifaddrs rows, NETLINK_ROUTE routes, NETLINK_SOCK_DIAG TCP/UDP sockets (capped 60 each), /proc/net/route, /proc/net/ipv6_route, /proc/net/dev, /proc/self/maps markers, library integrity, JVM NetworkInterface dump
- `[tunProbe]`: debugEnabled, modeOverride, resolver description, activeNetworkIsVpn, vpnNetworkPresent, underlyingNetworkPresent, per-path (VPN + underlying): available, interfaceName, selectedMode, selectedIp, strict/curl results with status/ip/error/engine/resolveStrategy/curlCode/httpCode/nativeLibraryLoaded/caBundleVersion

**Reference formatters:**
- `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/DebugDiagnosticsFormatter.kt`
- `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/TunProbeDiagnosticsFormatter.kt`

**RIPDPI placement:**
- `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/debug/DetectionDebugFormatter.kt`
- `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/debug/TunProbeDebugFormatter.kt`
- `DetectionCheckViewModel` — `copyDebugDiagnostics()` action gated on `debugMode=true` in settings
- Settings: `PREF_TUN_PROBE_DEBUG_ENABLED` (default false) → already in `add-detection-settings-screen` scope

## Acceptance criteria

- [ ] Debug mode toggle in Detection Settings (default OFF)
- [ ] When ON: "Copy diagnostics" action appears in `DetectionCheckScreen` menu/FAB after scan completes
- [ ] `DetectionDebugFormatter.format()` produces bracket-sectioned plain text matching the structure above
- [ ] Per-step timing included for `IndirectSignsChecker` sub-checks and `LocationSignalsChecker` BeaconDB + cell info steps
- [ ] `TunProbeDebugFormatter.formatSection()` embedded in the `[tunProbe]` section
- [ ] IPs in debug output respect privacy mode (masked when enabled)
- [ ] Raw native dumps capped: NETLINK sockets max 60 entries each; /proc/self/maps shows only marker lines (not full map)
- [ ] Output copyable to clipboard; no file write needed (in-memory string)
- [ ] Unit test: build a minimal `DetectionCheckResult` fixture; assert formatter produces non-empty output with all required bracket sections

## TDD workflow

1. **Write tests first** — build a minimal `DetectionCheckResult` fixture in a shared test helper:
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/debug/DetectionDebugFormatterTest.kt`:
     - `output_contains_all_required_bracket_sections()` — call `format(fixture)`; assert output contains `[indirectSigns.performance]`, `[locationSignals.debug]`, `[nativeSigns.raw]`, `[tunProbe]`; fails until formatter exists
     - `settings_section_lists_all_keys()` — assert output contains every key from `DetectionSettings` (check a representative set: `cdnPullingEnabled`, `dnsResolverMode`, `portRange`)
     - `native_sockets_capped_at_60()` — inject fixture with 80 TCP sockets; assert output contains at most 60 socket lines
     - `privacy_mode_masks_ips_in_debug_output()` — set `privacyMode=true`; inject fixture with IP `5.6.7.8`; assert output contains `5.6.*.*` not `5.6.7.8`
   - `core/detection/src/test/kotlin/com/poyka/ripdpi/core/detection/debug/TunProbeDebugFormatterTest.kt`:
     - `format_section_contains_per_path_fields()` — assert output contains `vpnPath.selectedMode`, `underlyingPath.available`; fails until formatter exists
2. **Confirm red** — `./gradlew :core:detection:test` — all 5 fail
3. **Implement** — `DetectionDebugFormatter`, `TunProbeDebugFormatter`, `copyDebugDiagnostics()` in `DetectionCheckViewModel`, debug toggle gate
4. **Confirm green** — `./gradlew :core:detection:test`
5. **Refactor** — extract section writers; share IP masking with `DetectionPrivacyMask`

## Definition of done

Unit test green. In debug mode, tapping "Copy diagnostics" puts the full formatted string on clipboard. Privacy mode masking applied.
