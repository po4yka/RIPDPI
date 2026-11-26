---
id: UIX-1795661310559744
title: Share AmneziaWG servers as full .conf files
kind: feature
status: review
area: ui
priority: medium
owner: unassigned
parent: EPC-1795615697939690
blocked_by: []
spec_mode: required
openspec_change: uix-1795661310559744-share-amneziawg-servers
created: 2026-11-26
updated: 2026-11-26
---

## Goal

The user can share a saved AmneziaWG server as a complete `.conf` file, from the same server actions menu that relay servers use.

## Context

Split from UIX-1795615852980919 (Manage all servers in one list). That change added rename, duplicate, share, and delete actions to every server row, but AmneziaWG rows have no Share action: no exporter turns a saved `AwgActivationRequest` back into a `.conf` file. A file without the AmneziaWG 3.x obfuscation fields (Jc, Jmin, Jmax, S1-S4, H1-H4, I1-I5) connects as plain WireGuard and fails against an AmneziaWG server, so a partial exporter is worse than none.

## Acceptance criteria

- An exporter writes a saved AmneziaWG server as a `.conf` file that carries the interface, peer, and every AmneziaWG 3.x obfuscation field the server has.
- Importing an exported file gives a server equal to the original, for plain WireGuard, AmneziaWG 1.x, and AmneziaWG 3.x servers.
- AmneziaWG rows in the Servers list offer Share in the server actions menu and on long-press, like relay rows.
- Share for a subscription-owned AmneziaWG server follows the same rule as subscription-owned relay servers.

## Ownership

One writer in a dedicated worktree owns the AmneziaWG exporter in `core/data`, its tests, and the AWG row actions in `app/.../connection/ServerListItems.kt` and `ServerListViewModel.kt`.

## Evidence

- Exporter: `AwgActivationRequest.toWgQuickConf()` in `core/data/runtime-state` maps the saved request onto the existing `AmneziaWgProfile.toWgQuickConf()` writer, so it writes the interface, peer, keepalive range, and every Jc/Jmin/Jmax/S1-S4/H1-H4/I1-I5 and v3 field. `isConfExportable` limits it to keyed UDP servers that pass `requireRuntimeReady()`.
- Share: local AWG rows get Share in the actions menu and on long press. `ServerListViewModel.shareAwg` loads the server and emits the conf; `AwgConfShareEffect` writes it to `cache/profile-share/<uuid>/<name>.conf` and opens the share sheet through the existing profile FileProvider. A failed or cancelled share deletes the file; `RipDpiApp` prunes shares older than 24 hours. `AwgConfShare.toString()` is redacted.
- Subscription rule: subscription relay members are never individual share targets, so subscription-owned AWG rows get no Share (rename stays). Decisions are in ADR 0016.
- Tests: `AwgActivationConfTest` (AWG 3 fields parse back exactly; plain server writes plain WireGuard; keyless, WebSocket, and invalid servers are refused); `AmneziaWgConfShareRoundTripTest` (export then import equals the original for plain WG, AWG 1.x, and AWG 3.x); `AmneziaWgSavedProfileEditTest` (only keyed local UDP servers are shareable); `ConnectionScreenTest` (share from the actions menu and long press; the subscription row has rename and no Share). The `ConnectionScreenTest` case failed before the fix with `onShare = null` (AssertionError at the share click); the other tests use APIs the fix adds and do not compile without it.
- Gates on the branch rebased onto main `bc95fe0e3e`: `./gradlew :core:data:testDebugUnitTest --tests '*AwgActivationConfTest'`, `./gradlew :app:lintGithubDebug`, `./gradlew :core:data:runtime-state:detekt :app:detekt` (0 findings), and `just ci-gates` exit 0. `./gradlew :app:verifyRoborazziGithubDebug` ran 4329 tests with 1 failure, `RipDpiNavHostLogicTest` "UI audit spec covers or explains every registered route" (stale tags `logs-actions`, `logs-filters` from main commits `c470ca7fbc`/`bc95fe0e3e`, not this change); all tests of this change pass and no goldens changed.
- Not checked: the share sheet on a device or emulator. Server names without Latin characters give the file name `amneziawg.conf`.
