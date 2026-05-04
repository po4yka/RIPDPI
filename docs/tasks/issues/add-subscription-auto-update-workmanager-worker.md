---
title: Add subscription auto-update WorkManager worker
type: task
status: backlog
area: outbound
priority: high
owner: unassigned
parent: epic-nekobox-subscription-and-profile-import
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add subscription auto-update WorkManager worker #repo/RIPDPI #area/outbound #status/backlog ⏫

## Summary

Schedule a WorkManager PeriodicWorkRequest that refreshes every auto-update
subscription at its configured cadence (min 15 min), gated by the "update
when connected only" group toggle.

## Context

NekoBox clamps the WorkManager interval to the shortest configured
`autoUpdateDelay` across all auto-updating groups. The worker runs in the
`:bg` service process via `work-multiprocess` so it shares lifecycle with
the tunnel supervisor. On boot, the boot receiver re-triggers schedule
reconciliation.

## Acceptance criteria

- [ ] PeriodicWorkRequest is registered via WorkManager with the shortest
    applicable interval (>= 15 min).
- [ ] Worker skips an entry if its `updateWhenConnectedOnly` is true and
    the VPN/proxy is not currently connected.
- [ ] Worker posts a foreground-notification during the refresh window
    via the `service-subscription` channel.
- [ ] Refresh reuses the HTTP client from `ripdpi-runtime` so in-proxy
    fetch works when the tunnel is up.
- [ ] Rate-limit: a manual refresh and an auto refresh for the same
    group within 30 s collapse into one network round-trip.
- [ ] Failure classification: network error, auth error, parse error —
    each with typed telemetry, not a generic "failed" toast.
- [ ] Boot receiver [[Add boot-completed receiver with dynamic enable]]
    re-registers the schedule on device boot.

## Source references

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/bg/SubscriptionUpdater.kt` — full reference: scheduling via `RemoteWorkManager` (multiprocess), `UpdateTask` as `CoroutineWorker`, min-interval clamp (15 min), shortest-auto-update-delay across all groups, `updateWhenConnectedOnly` gating, `cancelUniqueWork` on reconfigure.
- `app/src/main/java/io/nekohasekai/sagernet/group/GroupUpdater.kt` — `doUpdate()` orchestration: `updating` lock set, `userInterface.onUpdateSuccess()` callback, error-type classification (network/auth/parse).
- `app/src/main/AndroidManifest.xml` — `androidx.work:work-multiprocess` service declaration; worker runs in the `:bg` process.

**Adapt:** The multiprocess WorkManager pattern, the shortest-interval-clamp scheduling, typed error telemetry categories. **Skip:** NekoBox's in-proxy HTTP fetch via `Libcore.newHttpClient()` — RIPDPI should use its own in-tunnel HTTP client when the tunnel is up (falls back to direct when not), which is architecturally cleaner than NekoBox's one-path approach.

## Links

- [[Epic - NekoBox subscription and profile import]]
- [[Epic - Boot autostart and session persistence]]


## orchestration-test-posture
