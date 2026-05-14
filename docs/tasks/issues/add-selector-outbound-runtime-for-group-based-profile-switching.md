---
title: Add selector outbound runtime for group-based profile switching
type: task
status: backlog
area: outbound
priority: medium
owner: unassigned
parent: epic-nekobox-subscription-and-profile-import
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add selector outbound runtime for group-based profile switching #repo/RIPDPI #area/outbound #status/backlog 🔼

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
active without tearing down the service. Matches NekoBox's sing-box
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

**NekoBoxForAndroid** ([repo](https://github.com/MatsuriDayo/NekoBoxForAndroid), local: `/Users/po4yka/GitRep/NekoBoxForAndroid/`):

- `app/src/main/java/io/nekohasekai/sagernet/ui/SwitchActivity.kt` — the dialog-style transparent activity launched from the persistent notification's "Switch" action. Shows group member list with latency hints; tap triggers supervisor reload with new selectedProfile. **Port the UX pattern.**
- `app/src/main/java/io/nekohasekai/sagernet/bg/proto/ProxyInstance.kt` — hot-reload pathway when selectedProfile changes within a selector group. Search for `selectorGroupId` and `cbSelectorUpdate`.
- `app/src/main/java/io/nekohasekai/sagernet/bg/TileService.kt` — QS tile subtitle updates via `cbSelectorUpdate` callback.
- `app/src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt` — sing-box `selector` outbound generation (search for `"selector"` as `type`). Reference only — RIPDPI doesn't emit sing-box config.

**Adapt:** Notification "Switch" action, dialog profile list with latency hints, hot-reload via supervisor reload path (no full teardown), QS tile subtitle update. **Skip:** sing-box selector outbound JSON generation.

## Links

- [[Epic - NekoBox subscription and profile import]]
- [[Add ProxyGroup and Subscription entities to RIPDPI data layer]]
