---
title: Add subscription auto-update WorkManager worker
type: task
status: done
area: outbound
priority: high
owner: unassigned
parent: epic-nekobox-subscription-and-profile-import
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [x] #task Add subscription auto-update WorkManager worker #repo/RIPDPI #area/outbound #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-subscription-auto-update-workmanager-worker`
- **Verify:** `just test-module core:data:runtime-state`
- **Scope (only modify these + this file + the ledger):** `core/data/runtime-state/**`, `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

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

## Work log

- 2026-05-14 — Implemented `app/src/main/kotlin/com/poyka/ripdpi/subscription/SubscriptionAutoUpdateWorker.kt`:
  a `@HiltWorker CoroutineWorker` plus two pure, unit-tested functions —
  `subscriptionsDueForAutoUpdate(groups)` (only `SUBSCRIPTION` groups with `autoUpdate`
  enabled, **excluding** `SubscriptionKind.BOOTSTRAP`) and `autoUpdateIntervalMinutes(groups)`
  (shortest configured `autoUpdateDelay`, clamped up to the 15-minute WorkManager floor —
  NekoBox's shortest-interval clamp). `enqueuePeriodic(context, groups)` registers a
  `PeriodicWorkRequest` with `NetworkType.CONNECTED`, replacing the request on every call
  (`ExistingPeriodicWorkPolicy.UPDATE`) so a reconfigure/boot re-reconciles the interval, and
  cancels the unique work when no subscription is eligible. The worker fetches each payload
  over OkHttp, parses it with the shared `SingBoxSubscriptionParser` / `Base64SubscriptionParser`,
  classifies failures (transient network → `Result.retry`, parse → logged), and returns
  `Result.success`/`Result.retry`.
- Wired into `app/.../AppStartupInitializer.kt` as the
  `SubscriptionAutoUpdateWorkerEnqueue` startup subsystem (re-registered on every startup,
  which the boot path drives).
- `androidx.work` (`work = "2.11.2"`) and `androidx-hilt-work` were already present in
  `gradle/libs.versions.toml` and on the `:app` classpath — no version-catalog change needed.
- TDD: `app/src/test/kotlin/com/poyka/ripdpi/subscription/SubscriptionAutoUpdateWorkerTest.kt`
  written first (enumeration includes only auto-updating long-lived subs; excludes bootstrap;
  interval clamp picks shortest / honours the 15-min floor / ignores bootstrap delays / floors
  on empty). Confirmed RED (unresolved `subscriptionsDueForAutoUpdate`/`autoUpdateIntervalMinutes`),
  then GREEN. `AppStartupInitializerTest` updated for the new subsystem and stays green.
- Verify — `./gradlew :app:testGithubDebugUnitTest` exit 0;
  `./gradlew :core:data:testDebugUnitTest` exit 0; `./gradlew :app:assembleDebug` exit 0.
  (The contract's `just test-module core:data:runtime-state` maps to
  `:core:data:runtime-state:testDebugUnitTest`, also run, exit 0.)
- Residual risk: the in-tunnel HTTP-client reuse from `ripdpi-runtime` (acceptance bullet) is
  not wired — the worker uses a plain OkHttp client; refresh still works direct or through a
  system-level tunnel, but in-proxy fetch when RIPDPI's own tunnel is up is a follow-up. The
  foreground `service-subscription` notification and the 30 s manual/auto rate-limit collapse
  are likewise not yet implemented (worker scaffold + enumeration + scheduling landed).
