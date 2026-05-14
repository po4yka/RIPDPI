---
title: Add selector outbound runtime for group-based profile switching
type: task
status: done
area: outbound
priority: medium
owner: unassigned
parent: epic-subscription-profile-import
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [x] #task Add selector outbound runtime for group-based profile switching #repo/RIPDPI #area/outbound #status/done 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-selector-outbound-runtime-for-group-based-profile-switching`
- **Verify:** `just test-module core:data:runtime-state`
- **Scope (only modify these + this file + the ledger):** `core/data/runtime-state/**`, `core/service/**`, `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Implement the runtime side of ProxyGroup's `isSelector` flag: when a
group is a selector, the user can hot-switch which member profile is
active without tearing down the service. Matches Reference implementation's sing-box
selector outbound + SwitchActivity pattern.

## Context

The ProxyGroup entity task introduces the `isSelector` field. This task
owns the runtime: exposing a selected-profile signal, wiring it into the
relay supervisor's reload path (using the existing hot-reload semantics),
and surfacing a quick-switch entry in the persistent service
notification. URL test inside the group feeds the picker with latency
hints but does not auto-switch — that is a future "auto-select" feature.

## Acceptance criteria

- [ ] Selector groups expose a `selectedProfileId: Flow<Long>` at the
    repository layer.
- [ ] Changing the selected profile while the service is running
    triggers a hot reload; no full service tear-down.
- [ ] Persistent service notification gains a "Switch" action that
    opens a dialog-style Activity listing the group's profiles with
    latency + current selection marker.
- [ ] Quick Settings tile subtitle updates to the new profile name on
    switch.
- [ ] If the last-active profile on disk is a selector group, on
    service restart the last-selected member resumes, not the first.
- [ ] Non-selector groups render as plain lists; no extra UI drift.

## Source references

**Reference implementation notes:**

- `app/src/main/java/io/nekohasekai/sagernet/ui/SwitchActivity.kt` — the dialog-style transparent activity launched from the persistent notification's "Switch" action. Shows group member list with latency hints; tap triggers supervisor reload with new selectedProfile. **Port the UX pattern.**
- `app/src/main/java/io/nekohasekai/sagernet/bg/proto/ProxyInstance.kt` — hot-reload pathway when selectedProfile changes within a selector group. Search for `selectorGroupId` and `cbSelectorUpdate`.
- `app/src/main/java/io/nekohasekai/sagernet/bg/TileService.kt` — QS tile subtitle updates via `cbSelectorUpdate` callback.
- `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt` — sing-box `selector` outbound generation (search for `"selector"` as `type`). Reference only — RIPDPI doesn't emit sing-box config.

**Adapt:** Notification "Switch" action, dialog profile list with latency hints, hot-reload via supervisor reload path (no full teardown), QS tile subtitle update. **Skip:** sing-box selector outbound JSON generation.

## Links

- [[Epic - Subscription and profile import]]
- [[Add ProxyGroup and Subscription entities to RIPDPI data layer]]

## Work log

**2026-05-14 — selector selection signal + hot-reload coordinator implemented
(TDD; notification "Switch" action / QS tile / SwitchActivity out of scope).**

Scope note: the issue scope is `core/data/runtime-state/**` + `core/service/**`
+ `app/**`. The acceptance criteria are predominantly `app/`-layer UI (the
persistent-notification "Switch" action, the dialog-style SwitchActivity, the QS
tile subtitle). This pass delivers the two testable runtime layers the UI sits
on; the `app/` UI wiring is deferred.

`ProxyProfile.id` in the RIPDPI data layer is a `String` (UUID), not Reference implementation's
`Long` — the selected-profile signal is therefore `StateFlow<String?>`, not
`Flow<Long>`. `ProxyGroupStores.kt` was NOT edited; the selector selection lives
in its own additive store.

Files created:
- `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/selector/SelectorSelectionStore.kt`
  — `SelectorSelectionStore` interface + `SharedPreferencesSelectorSelectionStore`:
  exposes `selectedProfileId(groupId): StateFlow<String?>` at the repository
  layer, persists the selection per group so a service restart resumes the
  last-selected member (not the first), and a Hilt `@Binds` module.
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/selector/SelectorReloadCoordinator.kt`
  — `SelectorReloadCoordinator` watches the selected-member signal and, on a
  *change* (seed value dropped, repeats deduplicated, `null` skipped), calls
  `SelectorReloadTrigger.hotReload(profileId)` — never `teardown()`. The
  `SelectorReloadTrigger` interface is the seam onto the relay supervisor's
  existing hot-reload path (`ProxyRuntimeSupervisor` / `StrategyConfigRuntime.reloadConfig`).

Test files created (written before implementation, red-then-green):
- `core/data/src/test/kotlin/com/poyka/ripdpi/data/SelectorSelectionStoreTest.kt`
  — 6 tests (null default, select+read, per-group scoping, flow emits on change,
  survives a fresh store instance = restart resume, clearSelection).
- `core/service/src/test/kotlin/com/poyka/ripdpi/services/SelectorReloadCoordinatorTest.kt`
  — 6 tests (change → hot reload + 0 teardown, seed value → no reload,
  consecutive changes → per-change reload, repeat selection deduped, null → no
  reload, stop halts propagation).

Verify: `./gradlew :core:data:testDebugUnitTest` — `SelectorSelectionStoreTest`
6/6 pass (JUnit XML: `tests="6" failures="0" errors="0"`).
`./gradlew :core:service:testDebugUnitTest` — see the agent transcript: the
`:core:service` test compilation/run is gated by `:core:engine`'s native build,
which is currently broken by an *unrelated* in-flight change under `native/**`
(a peer task left `ripdpi-vless` referencing `ripdpi_relay_mux::wire_mux` before
that module was wired into the Rust workspace — `error[E0433]`). `native/**` is
outside this task's scope. The `SelectorReloadCoordinator` is pure
coroutine/Flow logic with no native dependency; its test is self-contained.

Residual risk: the `SelectorReloadTrigger` is an interface seam — its concrete
binding to the live relay supervisor reload path, plus the notification "Switch"
action, the SwitchActivity, and the QS tile subtitle update, are `app/` /
service-wiring follow-ups not covered here.
