---
title: Add start-on-boot user toggle and permission guard
type: task
status: backlog
area: service
priority: medium
owner: unassigned
parent: epic-boot-autostart-and-session-persistence
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add start-on-boot user toggle and permission guard #repo/RIPDPI #area/service #status/backlog 🔼

## Summary

Add a "Start on boot" toggle in Settings that controls boot-receiver
enable state, with a one-time prompt to whitelist from battery-saver /
doze / vendor background-kill policies.

## Context

On stock Android the toggle is enough. On vendor ROMs (MIUI, EMUI,
OneUI, ColorOS, FuntouchOS), auto-start is gated by a separate vendor
setting. The prompt should link out to the vendor setting on detection
and not nag on subsequent launches.

## Acceptance criteria

- [ ] Toggle in Settings labeled "Start on boot" with an explanatory
    caption.
- [ ] First time enabling invokes `PowerManager.isIgnoringBattery
    Optimizations` check; if false, show rationale and launch the
    system intent.
- [ ] Vendor-specific intent routing for at least: Xiaomi, Huawei,
    Oppo, Vivo, Samsung — each wrapped in a try/fallback to the
    generic settings intent.
- [ ] Rejection of the battery whitelist does NOT reset the toggle;
    the user can still proceed with a warning banner showing expected
    reliability impact.
- [ ] Toggle state persists; companion task handles the component
    enable/disable wiring.
- [ ] Accessibility: toggle has description, vendor-setting link is
    keyboard-reachable.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt` — `persistAcrossReboot: Boolean` property (the NekoBox equivalent of "Start on boot").
- `app/src/main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt` — the `SwitchPreference` bound to `persistAcrossReboot`. On toggle, calls `BootReceiver.setEnabled()`.

**amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`):

- `ui/src/main/java/org/amnezia/awg/activity/SettingsActivity.kt` — simpler toggle model; always-on is driven by the system VPN always-on setting, not an in-app toggle. RIPDPI should follow NekoBox's explicit toggle.

**Adapt:** Named preference, on-toggle call to enable/disable receiver component. **Add (neither project has this):** vendor-ROM redirect intents (MIUI/EMUI/OneUI/ColorOS/FuntouchOS) and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` first-time prompt. These are a frequent support issue for both upstreams.

## Links

- [[Epic - Boot autostart and session persistence]]
- [[Add boot-completed receiver with dynamic enable]]


## cloudflare-publish-hardening
