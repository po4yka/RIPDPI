---
id: UIX-1795615862573235
title: Correct onboarding permission prompts and settings labels
kind: bug
status: review
area: ui
priority: low
owner: unassigned
parent: EPC-1795615697939690
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-25
updated: 2026-11-26
spec_reason: regression-tested-single-module
---

## Goal

Onboarding and Settings open the right system screens and do not mislabel optional settings.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). The battery card's Open settings opens the list of all apps (`BatteryOptimizationIntents.kt` uses `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`). The Always-on VPN card has no button. After Skip setup, the notification permission prompt appears only after Disconnect. Settings labels Always-on VPN and Block connections without VPN as `Required`.

## Acceptance criteria

- The battery action opens the RIPDPI entry or the request dialog, subject to Play policy.
- The Always-on VPN card has an action that opens VPN settings.
- The notification permission is requested before the first connection on the skip path.
- Optional settings are not labelled Required in any locale.

## Ownership

One writer in a dedicated worktree owns onboarding permission cards, `BatteryOptimizationIntents.kt`, Settings rows, and strings in `app`. Locale resource sets are a serialized lane: only this writer changes them while the task is in progress..
