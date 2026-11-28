---
id: UIX-1795615862795492
title: Review the VPN-detected notification and route labels
kind: research
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
spec_reason: research-only
status_detail: "Merged; local gates and remote CI passed (run 46009834608 on d9aa00b99f); a device run remains: connect on a clean install and confirm that no VPN detected notification appears on the first check and that Details on Home has no Route row."
---

## Goal

Decide whether a new user should get the `VPN detected` notification without asking, and how to name routes in Details.

## Context

Found in the 2026-11-25 on-device user-flow audit (Pixel 8 Pro, githubDebug at 66673515dd, clean install). During the audit an unprompted notification `VPN detected: score 31/100` arrived. The Details panel shows `Route 6`, which a new user cannot interpret.

## Acceptance criteria

- A written decision states when the VPN-detected notification fires and what it tells the user.
- A written decision states the user-facing route label, with follow-up tasks if code changes are needed.

## Ownership

One writer in a dedicated worktree owns `core/detection/.../DetectionCheckScheduler.kt`, `app/.../ui/screens/status/StatusStrategyLabel.kt`, their tests, the `status_stat_route` string in all 10 locales, and `config/i18n/translatable-keys.txt`.

## Decision

### VPN-detected notification

Before: the background quick check runs each time the VPN reaches Running and on each network handover. It posted `VPN detected: score N/100` (or `VPN may be visible: score N/100`) whenever the verdict was `DETECTED` or the stealth score was below 50. The quick check reads only on-device signs (VPN transport, tun interface, installed VPN apps), which every running VPN shows, so a new user got the alert on the first connect, unprompted and with no action to take.

Decision: the notification fires only when the result for a network gets worse. The first check on a network saves its result as the baseline and posts nothing. A later check posts the alert only if it is alarming (verdict `DETECTED` or score below 50) and the saved result for the same network was not. The alert keeps its text: the verdict and score in the title, and `Tap to open Detection check for details and fixes.` as the body, which opens Detection check. Every result is still saved to the detection history, so Detection check shows it without a notification. No setting is added.

### Route label in Details

Before: the Details panel on Home showed a `Route` row with the native desync group index (`Route 6`). The index names a position in the generated strategy config, not anything the user chose or can find elsewhere; the panel title already shows the TCP chain the index points to.

Decision: Details shows no route label. The row and the `status_stat_route` string are removed from all 10 locales. The route group still feeds the strategy identity (`stableId`) and stays in Diagnostics and exports as `Route group`, where it is a technical field. No follow-up task is needed.


## Evidence

- Before the fix, `./gradlew :core:detection:testDebugUnitTest --tests '*DetectionCheckSchedulerTest' :app:testGithubDebugUnitTest --tests '*StatusStrategyLabelTest'` failed: `first check on a network saves the result without an alert` (an alert was posted) and all 4 `StatusStrategyLabelTest` cases (the `Route`/`Маршрут`/`مسیر` row was present).
- After the fix, `./gradlew :core:detection:testDebugUnitTest :app:verifyRoborazziGithubDebug`: detection tests pass, including the alert test that now seeds a clean saved result for the network; app 4326 tests ran with no screenshot differences and 1 failure, `ProfileUtilityFastestSelectionTest` (`IllegalStateException` from `TestMainDispatcher`, a Main-dispatcher leak between test classes, unrelated to this diff). It passes alone: `./gradlew :app:testGithubDebugUnitTest --tests '*ProfileUtilityFastestSelectionTest*'` exit 0.
- `./gradlew :app:lintGithubDebug :core:service:lintStandardDebug :core:detection:lintDebug` exit 0.
- `./gradlew :core:detection:detekt` exit 0.
- `just ci-gates` exit 0.
- After rebasing onto local `main` (bc95fe0e3e), `./gradlew :core:detection:testDebugUnitTest :app:testGithubDebugUnitTest --tests 'com.poyka.ripdpi.ui.screens.status.*' --continue`: status tests and `DetectionCheckSchedulerTest` pass; `PingCommandProfileTest` (`iputils uses numeric output without reverse DNS`) failed twice with `replied == false` while the host load average was about 830. This branch does not touch that test or `SystemPingProber`, and it passed in the pre-rebase full detection run.
- Not checked on a device.
- Remote CI: run 46009834608 on main d9aa00b99f passed (35 jobs, no failures).
