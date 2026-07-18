---
title: Disable Simple disconnect controls under Android lockdown
type: task
status: doing
area: ui
priority: medium
owner: Codex Simple lockdown lane
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-07-18
updated: 2026-07-18
---

## Summary

Keep the Simple flavor control plane consistent with Android Always-on VPN plus
Block connections without VPN. The service already rejects a stop request while
lockdown is active and the notification omits its Stop action, but the Simple home
screen still presents an enabled Disconnect button that appears to work and then
silently leaves the VPN running.

## Acceptance criteria

- [ ] An active Simple session exposes no enabled disconnect control while the
  platform lockdown policy blocks disconnect.
- [ ] `MainViewModel` rejects stale or non-UI stop requests when its current
  hard-kill-switch state blocks disconnect.
- [ ] Compose and ViewModel regression tests cover connected unlocked, connected
  locked, disconnected, and diagnostic-busy states.
- [ ] A physical Pixel run through the official Android VPN settings confirms that
  the Simple control, notification action, and service handler agree.

## Evidence

- 2026-07-18: Pixel 7 / API 37, configured through the official VPN Settings UI:
  the service kept the live VPN after a Simple Disconnect tap and the foreground
  notification contained no Stop action, while Simple still rendered an enabled
  Disconnect button. Always-on and lockdown were restored to their original state
  after the observation.
