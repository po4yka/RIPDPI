---
title: Add Detection Settings Screen with Full Feature Toggle and DNS Config
type: task
status: doing
area: ui
priority: high
owner: unassigned
parent: detection-feature-parity-epic
blocks: []
blocked_by: []
created: 2026-05-10
updated: 2026-05-10
---

- [ ] #task Add Detection Settings Screen with Full Feature Toggle and DNS Config #repo/RIPDPI #area/ui #status/doing ⏫

## Objective

Add a Detection Settings screen in Jetpack Compose that exposes all configurable detection checker options: feature toggles, DNS resolver mode, port scan range, privacy mode, and debug mode.

## Context

RIPDPI's existing `DetectionCheckPreferences.kt` is a stub. All new checkers added in this epic have configurable defaults (CDN pulling off, RTT off, call transport off, etc.). Users need a way to enable them. RKNHardering exposes these across 6 settings categories. RIPDPI should merge these into a single Detection Settings sheet/screen accessible from `DetectionCheckScreen`.

**Reference settings categories from RKNHardering:**
- Network: master toggle, CDN pulling + meduza sub-toggle, call transport probe, RTT triangulation, auto-update check
- Split tunnel: proxy scan, Xray API scan, TUN probe mode (Auto/Strict/CurlCompat), port range (Popular/Extended/Full/Custom)
- DNS: resolver mode chips (System/Direct/DoH), preset chips (Custom/Cloudflare/Google/Yandex), server inputs
- Privacy: IP masking toggle
- Appearance: theme (System/Light/Dark), language (System/EN/RU), color vision mode (Off/RedGreen/BlueYellow/Achromatopsia)
- Debug: TUN probe debug toggle (enables copy-diagnostics action)

**Reference settings files:**
- `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/settings/SettingsNetworkFragment.kt`
- `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/settings/SettingsDnsFragment.kt`
- `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/settings/SettingsSplitTunnelFragment.kt`
- `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/settings/SettingsPrivacyFragment.kt`
- `/Users/po4yka/GitRep/RKNHardering/app/src/main/java/com/notcvnt/rknhardering/SettingsPrefs.kt` — all 32 preference keys with defaults

**RIPDPI placement:**
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionSettingsScreen.kt` (new)
- `DetectionCheckPreferences.kt` — extend or replace with DataStore-backed `DetectionSettings`
- `DetectionCheckViewModel` — expose `detectionSettings: StateFlow<DetectionSettings>`

## Acceptance criteria

- [ ] Settings reachable from `DetectionCheckScreen` via gear icon / bottom sheet
- [ ] **Network toggles**: CDN Pulling (off by default, confirmation dialog on enable), CDN Pulling Meduza sub-option (on by default), Call Transport Probe (off), RTT Triangulation (off); disabled cards shown at `alpha=0.38f`
- [ ] **Split tunnel toggles**: Proxy Scan (on), Xray API Scan (on), TUN Probe Mode chips (Auto/StrictSamePath/CurlCompatible), Port Range chips (Popular/Extended/Full/Custom) with custom start/end inputs and live port count preview
- [ ] **DNS resolver**: mode chips (System/Direct/DoH), preset chips (Custom/Cloudflare/Google/Yandex); preset auto-populates + locks server fields; only Custom allows editing
- [ ] **Privacy mode toggle**: masks IPs in UI and export when enabled
- [ ] **Debug toggle**: enables "Copy diagnostics" action in DetectionCheckScreen
- [ ] All settings persisted to `DetectionSettings` DataStore; changes take effect on next scan run
- [ ] Settings read by `DetectionResolverNetworkStack` and all checker implementations
- [ ] Roborazzi goldens for each settings section

## TDD workflow

1. **Write tests first** — create before any implementation:
   - `app/src/test/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionSettingsViewModelTest.kt`:
     - `cdn_pulling_toggle_persisted_to_datastore()` — call `setCdnPullingEnabled(true)`; collect `DetectionSettings` flow; assert `cdnPullingEnabled=true`; fails until ViewModel + DataStore wiring exists
     - `dns_preset_cloudflare_auto_populates_server_fields()` — call `selectDnsPreset(CLOUDFLARE)`; assert `dnsDirectServers` = `"1.1.1.1,1.0.0.1"` and fields locked; fails until preset logic exists
     - `custom_dns_preset_allows_editing()` — call `selectDnsPreset(CUSTOM)`; assert `dnsFieldsEditable=true`
     - `disabled_feature_alpha_state_exposed_in_ui_state()` — `networkRequestsEnabled=false`; assert all dependent feature states marked `alpha=0.38f` in `DetectionSettingsUiState`
   - `app/src/screenshotTest/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionSettingsScreenTest.kt` — Roborazzi goldens for: Network section, DNS section (System/Direct/DoH each), Split Tunnel section; fails (no goldens recorded yet)
2. **Confirm red** — `./gradlew :app:test` — ViewModel tests fail; `./gradlew :app:recordRoborazziDebug` — no goldens yet
3. **Implement** — `DetectionSettingsScreen`, `DetectionSettingsViewModel`, `DetectionSettings` DataStore schema
4. **Confirm green** — `./gradlew :app:test`; record goldens; `./gradlew :app:verifyRoborazziDebug`
5. **Refactor** — extract each settings section into a separate composable

## Definition of done

All 32 preference keys have UI controls. Changing DNS mode and re-running detection uses the new resolver. Goldens pass.
