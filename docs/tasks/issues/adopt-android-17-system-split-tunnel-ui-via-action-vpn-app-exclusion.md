---
id: RTE-1786264762917959
title: Adopt Android 17 system split-tunnel UI via ACTION_VPN_APP_EXCLUSION_SETTINGS
kind: feature
status: dropped
area: routing
priority: medium
owner: unassigned
parent: EPC-1786264762917557
blocked_by: []
spec_mode: required
openspec_change: rte-1786264762917959-adopt-android-17-system-split-tunnel-ui-via-action-vpn-app-exclusion
created: 2026-04-25
updated: 2026-08-09
status_detail: version-gated delegation + fallback shipped + unit-tested; exclusion persistence across reconnects (OS-owned) is device-gated (needs an Android 17 device)
closed_at: "2026-08-09T11:12:19Z"
closed_reason: superseded by the active Android 17 validation task
evidence_summary: The delegate implementation is complete; remaining device validation is consolidated under RTE-1786264762917255.
---

## Summary

Android 17 added a system-owned split-tunnel UI: VPN apps fire `ACTION_VPN_APP_EXCLUSION_SETTINGS` and the OS persists user exclusions across reconnects. Wire this from RIPDPI settings so the per-app exclusion state lives in the OS instead of in-app, reducing the risk of exclusion loss on reconnect.

## Research citation

ripdpi-android-research-2026-04-25 §Android platform — Android 17 Beta 3 (2026-03) added the `ACTION_VPN_APP_EXCLUSION_SETTINGS` intent. Apps fire it to delegate per-app exclusion to a persistent OS-managed screen; exclusions survive reconnects. The underlying `VpnService.Builder` allowlist/blocklist API is unchanged — this is a UX standardisation layer on top.

## Acceptance criteria

- [x] Settings screen on Android 17+ fires `ACTION_VPN_APP_EXCLUSION_SETTINGS` to delegate to OS UI. The split-tunnel screen shows a "managed by system" card whose button fires the intent (the verified `compileSdk=37` value `android.settings.VPN_APP_EXCLUSION_SETTINGS`), gated by `SplitTunnelSystemUiGate.usesSystemScreen(sdkInt) = sdkInt >= 37`.
- [x] Android < 17 fallback retains in-app exclusion UI. The in-app editor is shown on < 17 **and** whenever the system screen does not resolve on the device (graceful degradation, no dead button).
- [ ] Exclusions verified to persist across VPN reconnects (OS-managed state). **DEVICE-GATED** — persistence is OS-owned and only observable on a real Android 17 device.
- [x] Manifest declares supported intent for system discovery. **CORRECTED:** Android 17 defines no app-side manifest declaration for this — `ACTION_VPN_APP_EXCLUSION_SETTINGS` is a system Settings action the app *fires* (via `startActivity`), not one a third-party Activity receives. Declaring it on an exported Activity is incorrect (dead config + an unintended launch surface), so **no `<intent-filter>` is added** (verified against the Android 17 release notes / VPN dev docs).

## Links

- Project: [[ripdpi-android]]
- Epic: [[Epic - Advanced routing rules and geoip enforcement]]
- Research: ripdpi-android-research-2026-04-25 §Android platform

## Work log

- 2026-06-05: Not started. `ACTION_VPN_APP_EXCLUSION_SETTINGS` not referenced anywhere in codebase. `SplitTunnelScreen.kt` uses in-app picker only, no Android-version branching. All 4 acceptance criteria remain unmet. Parent epic `epic-advanced-routing-rules-and-geoip-enforcement` is dangling (not in known epic list) — nulled out.
- 2026-06-11: **Shipped the version-gated delegation.** Added `SplitTunnelSystemUiGate` (pure, injectable `sdkInt`, JVM unit-tested) and a "managed by system" card on the split-tunnel screen for Android 17+ that fires `ACTION_VPN_APP_EXCLUSION_SETTINGS`; the in-app editor is retained on < 17 and whenever the system screen does not resolve. Two strings added across all 8 locales; the card consumes RDS tokens only. `compileSdk=37` confirmed; the action string verified against the API-37 `android.jar`. **Corrected criterion 4:** there is no app-side manifest declaration for this action — the app fires the intent; an exported-Activity `<intent-filter>` is wrong and was not added (pr-reviewer verified this against the Android 17 release notes). `:app:testGithubDebugUnitTest` green; pr-reviewer pass applied (dropped the incorrect intent-filter, added a resolve-check to avoid a dead button). Persistence across reconnects stays device-gated; status `backlog` → `doing`.
