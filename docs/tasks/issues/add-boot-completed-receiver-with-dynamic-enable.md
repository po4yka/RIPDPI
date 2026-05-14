---
title: Add boot-completed receiver with dynamic enable
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

- [ ] #task Add boot-completed receiver with dynamic enable #repo/RIPDPI #area/service #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-boot-completed-receiver-with-dynamic-enable`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Add a `BootReceiver` that handles `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`,
and `MY_PACKAGE_REPLACED`, toggled on only when the user has enabled
"Start on boot".

## Context

reference implementation enables the receiver component dynamically via
`PackageManager.setComponentEnabledSetting` so the broadcast filter only
exists while needed. Default state must be `DISABLED`; enabling it without
user opt-in is both a battery concern and a surprise behavior.

## Acceptance criteria

- [ ] `BootReceiver` declared in manifest with
    `android:enabled="false"` and filters for all three actions.
- [ ] Runtime enable/disable driven by a single repository method wired
    to the Settings toggle.
- [ ] `RECEIVE_BOOT_COMPLETED` permission declared.
- [ ] On fire, the receiver re-schedules subscription auto-update
    WorkManager job and, if active-profile exists + "start on boot" is
    on, starts the appropriate service mode.
- [ ] `MY_PACKAGE_REPLACED` path is gated by the "was running before
    update" flag (see companion task).
- [ ] Receiver work is short and offloads to a WorkManager one-shot;
    no heavy work in `onReceive`.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/BootReceiver.kt` — the full receiver. Handles `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`. Dynamic enable via `PackageManager.setComponentEnabledSetting(ComponentName, COMPONENT_ENABLED_STATE_{ENABLED,DISABLED}, DONT_KILL_APP)`.
- `app/src/main/AndroidManifest.xml` — receiver declaration with `android:enabled="false"` initially and the three intent-action filters.
- Companion object `setEnabled(enabled: Boolean)` — the API wired to the Settings toggle.

**amneziawg-android** ([repo](https://github.com/amnezia-vpn/amneziawg-android), local: `/Users/po4yka/GitRep/amneziawg-android/`) — cross-reference for the WireGuard-ecosystem pattern:

- `ui/src/main/java/org/amnezia/awg/BootShutdownReceiver.kt` — handles both `BOOT_COMPLETED` and `ACTION_SHUTDOWN` (save-state-on-shutdown is a WireGuard pattern absent from reference implementation; consider adopting).

**Adapt:** Dynamic-enable pattern, three-action filter set. **Consider:** adding `ACTION_SHUTDOWN` handler from AWG pattern to persist clean-shutdown flag. **Skip:** Reference implementation's subscription-updater re-registration (handled by WorkManager persistence in RIPDPI).

## Links

- [[Epic - Boot autostart and session persistence]]
